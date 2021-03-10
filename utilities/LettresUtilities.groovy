@groovy.transform.BaseScript com.ibm.dbb.groovy.ScriptLoader baseScript

import com.ibm.dbb.repository.*
import com.ibm.dbb.dependency.*
import com.ibm.dbb.build.*
import groovy.transform.*
import groovy.json.JsonSlurper
import com.ibm.dbb.build.DBBConstants.CopyMode

// define script properties
@Field BuildProperties props = BuildProperties.getInstance()
@Field HashSet<String> copiedFileCache = new HashSet<String>()
@Field def buildUtils= loadScript(new File("${props.zAppBuildDir}/utilities/BuildUtilities.groovy"))

def copySourceFiles(String buildFile, String srcPDS, String dependencyPDS, String dependencyDIR) {
	// only copy the build file once
	println buildUtils.getAbsolutePath(buildFile)
	if (!copiedFileCache.contains(buildFile)) {
		copiedFileCache.add(buildFile)
		new CopyToPDS().file(new File(buildUtils.getAbsolutePath(buildFile)))
				.dataset(srcPDS)
				.member(CopyToPDS.createMemberName(buildFile))
				.execute()
	}

	// resolve the logical dependencies to physical files to copy to data sets
	if (dependencyPDS && dependencyDIR) {
		List<PhysicalDependency> physicalDependencies = getDependencies(buildFile, srcPDS, dependencyDIR)
		if (props.verbose) println "*** Physical dependencies for $buildFile:"

		physicalDependencies.each { physicalDependency ->
			if (props.verbose) println physicalDependency
			if (physicalDependency.isResolved()) {
				String physicalDependencyLoc = "${physicalDependency.getSourceDir()}/${physicalDependency.getFile()}"

				// only copy the dependency file once per script invocation
				if (!copiedFileCache.contains(physicalDependencyLoc)) {
					copiedFileCache.add(physicalDependencyLoc)

					new CopyToPDS().file(new File(physicalDependencyLoc))
							.dataset(dependencyPDS)
							.member(CopyToPDS.createMemberName(physicalDependency.getFile()))
							.execute()
					}
				}
			}
		}
}

def getDependencies(String buildFile, String srcPDS, String dependencyDIR) {
	List<String> dependenciesNames = scanSource(buildFile, srcPDS)
	List<PhysicalDependency> physicalDependencies = []
	List<LogicalDependency> logicalDependencies = []
	dependenciesNames.each { name -> 
			LogicalDependency logicalDependency = new LogicalDependency(name, "COPY", "SYSLIB")
			logicalDependencies.add(logicalDependency)
			physicalDependencies.add(new PhysicalDependency(logicalDependency, props.applicationCollectionName, getAbsolutePath(dependencyDIR), name))
			}
	return physicalDependencies
}

def scanSource(String buildFile, String srcPDS) {
// create mvs commands
	String member = CopyToPDS.createMemberName(buildFile)
	
	File logFile = new File( props.userBuild ? "${props.buildOutDir}/${member}_scan.log" : "${props.buildOutDir}/${member}_scan.lettres.log")
	if (logFile.exists())
		logFile.delete()
		
	File dependencyListFile = new File( "${props.buildOutDir}/scanList_${member}.txt")
	if (dependencyListFile.exists())
		dependencyListFile.delete()

	MVSExec scan = createScanCommand(buildFile, srcPDS, member, dependencyListFile, logFile)

	// execute mvs commands in a mvs job
	MVSJob job = new MVSJob()
	job.start()

	// scan the lettres dependencies
	int rc = scan.execute()
	int maxRC = 4

	if (rc > maxRC) {
		bindFlag = false
		String errorMsg = "*! The scanner return code ($rc) for $buildFile exceeded the maximum return code allowed ($maxRC)"
		println(errorMsg)
		props.error = "true"
		buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}_scan.log":logFile],client:getRepositoryClient())
	}

	// clean up passed DD statements
	job.stop()
	
	if (rc <= maxRC) 
		return parseDependencyList(dependencyListFile)
	
}

def createScanCommand(String buildFile, String srcPDS, String member, File dependencyListFile, File logFile) {

	// define the MVSExec command to compile the program
	MVSExec scan = new MVSExec().file(buildFile).pgm("PCPY05")

	scan.dd(new DDStatement().name("PCPY0501").dsn("$srcPDS($member)").options('shr'))
	scan.dd(new DDStatement().name("PCPY0502").output(true))
	scan.dd(new DDStatement().name("SYSOUT").output(true))

	// add a copy command to the scan command to copy the SYSPRINT from the temporary dataset to an HFS log file
	scan.copy(new CopyToHFS().ddName("PCPY0502").file(dependencyListFile).hfsEncoding(props.logEncoding))
	scan.copy(new CopyToHFS().ddName("SYSOUT").file(logFile).hfsEncoding(props.logEncoding))
	
	return scan
}

def parseDependencyList(File dependencyListFile) {
	
		// define the MVSExec command to compile the program
		List<String> names = []
		dependencyListFile.eachLine { line ->
			def match = (line =~ /MEMBER NAME\s?=\s?(.+)/)
			if (match.find())
				names.add(match.group(1))
		}
		
		return names
	}