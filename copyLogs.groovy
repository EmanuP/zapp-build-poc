import com.ibm.dbb.build.*
import com.ibm.dbb.dependency.*
import com.ibm.dbb.build.report.*
import com.ibm.dbb.build.report.records.*
import groovy.time.*
import groovy.transform.*

/**
 * This script copies the load modules found in a build result to another PDS.
 *
 * usage: copyLogs.groovy [options]
 *
 * options:
 *  -a,--application              Application directory name (relative to workspace)	 
 *  -w,--workDir <dir>            Absolute path to the DBB build output directory
 *  -d,--destination <PDS>        Name of the destination PDS
 *  -l,--logEncoding			  (Optional) Defines the Encoding for output files (JCL spool, reports), default UTF-8
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

// Copy the log for each deployable load.
	println("** Copying Logs: ")

	executes.each{ execute ->
		execute.getLogFiles().each{ log ->
			copyLog(log, props.destination)
	}
}

def parseInput(String[] cliArgs){
	def cli = new CliBuilder(usage: "copyLogs.groovy [options]", header: "Command Line Options:")
	cli.w(longOpt:'workDir', args:1, 'Absolute path to the DBB build output directory')
	cli.d(longOpt:'destination', args:1, 'Name of the destination PDS')
	cli.a(longOpt:'application', args:1, 'Application directory name (relative to workspace)')
	cli.l(longOpt:'logEncoding', args:1, '(Optional) Defines the Encoding for output files, default UTF-8')
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

def copyLog(File logToCopy, String destination){

	String memberName = logToCopy.toString().tokenize('/').last().tokenize(".").first()
	memberName = CopyToPDS.createMemberName(memberName)

	try {
		new CopyToPDS().file(logToCopy)
				.dataset(destination)
				.member(memberName)
				.execute()
		println "**** ${logToCopy.getName()} successfully copied!" 
	} catch (BuildException e){
		println "!!!!  Encountered an exception while copying ${logToCopy.getName()}: ${e}"
	}	

}