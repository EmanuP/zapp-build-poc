import com.ibm.dbb.build.*
import com.ibm.dbb.dependency.*
import com.ibm.dbb.build.report.*
import com.ibm.dbb.build.report.records.*
import groovy.time.*
import groovy.transform.*

/**
 * This script copies the load modules found in a build result to another PDS.
 *
 * usage: copyLoads.groovy [options]
 *
 * options:
 *  -a,--application              Application directory name (relative to workspace)	 
 *  -w,--workDir <dir>            Absolute path to the DBB build output directory
 *  -d,--destination <PDS>        Name of the destination PDS
 *  -s,--source <PDS>			  (Optional) Name of the source PDS, default from DBB build report
 *  -l,--logEncoding			  (Optional) Defines the Encoding for output files (JCL spool, reports), default UTF-8
 *  -p,--preview				  (Optional) Preview JCL, do not submit it
 *  -h,--help                     Prints this message
 *
 */

// parse input arguments
@Field BuildProperties props = parseInput(args)


def startTime = new Date()
props.startTime = startTime.format("yyyyMMdd.hhmmss.mmm")
println("** Load modules copy started at $props.startTime")
println("** Properties at startup:")
props.each{k,v->
	if(v) println "   $k -> $v"
}

// read build report data
println("** Read build report data from $props.workDir/BuildReport.json")
def jsonOutputFile = new File("${props.workDir}/BuildReport.json")

if(!jsonOutputFile.exists()){
	println("** Build report data at $props.workDir/BuildReport.json not found")
	System.exit(1)
}

def buildReport= BuildReport.parse(new FileInputStream(jsonOutputFile))

// parse build report to find the build result meta info
def buildResult = buildReport.getRecords().findAll{it.getType()==DefaultRecordFactory.TYPE_BUILD_RESULT}[0];

// parse build report to find the build outputs to be deployed.
println("** Find deployable outputs in the build report ")

// finds all the build outputs with a "LOAD" deployType
def executes = buildReport.getRecords().findAll{
	try {
		it.getType()==DefaultRecordFactory.TYPE_EXECUTE &&
				!it.getOutputs().findAll{ o ->
					o.deployType == 'LOAD'
				}.isEmpty()
	} catch (Exception e){}	
}

executes.each { it.getOutputs().each { println("   ${it.dataset}")}}

if ( executes.size() == 0 ) {
	println("** No items to copy. Skipping copy jobs.")
	System.exit(0)
}

// Creates a map of member lists (datasetName -> List(members)).

def loadMap = [:].withDefault{ [] }

executes.each{ execute ->
	execute.getOutputs().each{ output ->
		def (ds,member) = getDatasetName(output.dataset)
		if(!props.source)
			loadMap[ds].add(member)
		else
			loadMap[props.source].add(member)
	}
}

if (props.preview.toBoolean()){
// Preview the JCL for each source PDS.
	println("** Previewing copy JCLs: ")
}
else {
// Create and execute a copy job for each source PDS.
	println("** Copying Load Modules: ")
}

def dbbConf = System.getenv("DBB_CONF")
loadMap.each { ds, loadList -> 

	println("** Dataset: $ds")

	String jcl = generateJCL(ds, props.destination, loadList)

	if (props.preview.toBoolean()){
		println "****  Preview only: "
		println()
		println jcl
		println()
	}
	else {
		// Create jclExec
		def copyJCL = new JCLExec().text(jcl)
		//copyJCL.confDir(dbbConf)

		// Execute jclExec
		copyJCL.execute()

		// Save Job Spool to logFile
		File logFile = new File("${props.workDir}/${ds}_copyLoads.log")
		copyJCL.saveOutput(logFile, props.logEncoding)

		// Print message according to job return code
		println("**  Submitting job: ${copyJCL.getSubmittedJobId()}")
		if(copyJCL.getMaxRC()[1,2] == "RC" && Integer.parseInt(copyJCL.getMaxRC()[-4..-1]) <= 4) println "****  Members of ${ds} successfully copied!" 
		else println "!!!!  Error: RC of ${ds} copy job exceeded 4."
	}
}

/**
 * parse data set name and member name
 * @param fullname e.g. BLD.LOAD(PGM1)
 * @return e.g. (BLD.LOAD, PGM1)
 */
def getDatasetName(String fullname){
	def ds,member;
	def elements =  fullname.split("[\\(\\)]");
	ds = elements[0];
	member = elements.size()>1? elements[1] : "";
	return [ds, member];
}

def parseInput(String[] cliArgs){
	def cli = new CliBuilder(usage: "copyLoads.groovy [options]", header: "Command Line Options:")
	cli.w(longOpt:'workDir', args:1, 'Absolute path to the DBB build output directory')
	cli.d(longOpt:'destination', args:1, 'Name of the destination PDS')
	cli.a(longOpt:'application', args:1, 'Application directory name (relative to workspace)')
	cli.s(longOpt:'source', args:1, '(Optional) Name of the source PDS, default from DBB build report')
	cli.l(longOpt:'logEncoding', args:1, '(Optional) Defines the Encoding for output files, default UTF-8')
	cli.p(longOpt:'preview', '(Optional) Preview JCL for CR, do not submit it')
	cli.h(longOpt:'help', 'Prints this message')
	def opts = cli.parse(cliArgs)
	if (opts.h) { // if help option used, print usage and exit

		cli.usage()
		System.exit(0)
	}

	def props = new Properties()

	// set command line arguments
	if (opts.w) props.workDir = opts.w
	if (opts.d) props.destination = opts.d
	if (opts.a) props.application = opts.a
	props.logEncoding = (opts.l) ? opts.l : "UTF-8"
	props.source = (opts.s) ? opts.s : ""
	props.preview = (opts.p) ? 'true' : 'false'

	// load application from ./application.properties if it exists
	def copyProperties = new Properties()
	def appConfRootDir = new File("${props.workDir}").parent
	def buildPropFile = new File("${appConfRootDir}/${props.application}/application-conf/application.properties")
	println(buildPropFile)

	if (buildPropFile.exists()){
		buildPropFile.withInputStream { copyProperties.load(it) }
		if (copyProperties.jobCard != null)
			props.jobCard = copyProperties.jobCard
	}

	// validate required properties
	try {
		assert props.workDir: "Missing property: build work directory"
		assert props.destination: "Missing property: destination PDS"
		assert props.jobCard: "Missing property: jobcard"
		assert props.application: "Missing property: application"
	} catch (AssertionError e) {
		cli.usage()
		throw e
	}
	return props
}

def generateJCL(String sourcePDS, String destinationPDS, List<String> loadList){

	String jcl = props.jobCard.replace("\\n", "\n")

	jcl += """\n//***************************************************************
//* IEBCOPY TO MOVE LOAD MODULES FROM ONE DATASET TO ANOTHER
//***************************************************************
//COPYLOAD EXEC PGM=IEBCOPY
//SYSPRINT DD SYSOUT=A
//SYSUT1 DD DSN=${sourcePDS},DISP=SHR
//SYSUT2 DD DSN=${destinationPDS},DISP=OLD,
// UNIT=SYSDA,SPACE=(CYL,(5,5))
//SYSIN DD *
       COPY OUTDD=SYSUT2,INDD=SYSUT1
"""
	loadList.each { loadMember -> 
		jcl += "       SELECT MEMBER=${loadMember}"
	}
	jcl += """\n/*
//
"""

	return jcl

}