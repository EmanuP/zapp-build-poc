@groovy.transform.BaseScript com.ibm.dbb.groovy.ScriptLoader baseScript
import com.ibm.dbb.repository.*
import com.ibm.dbb.dependency.*
import com.ibm.dbb.build.*
import groovy.transform.*
import com.ibm.jzos.ZFile

// define script properties
@Field BuildProperties props = BuildProperties.getInstance()
@Field def buildUtils= loadScript(new File("${props.zAppBuildDir}/utilities/BuildUtilities.groovy"))
@Field def lettreUtils= loadScript(new File("${props.zAppBuildDir}/utilities/LettresUtilities.groovy"))
@Field def impactUtils= loadScript(new File("${props.zAppBuildDir}/utilities/ImpactUtilities.groovy"))
@Field def bindUtils= loadScript(new File("${props.zAppBuildDir}/utilities/BindUtilities.groovy"))
@Field RepositoryClient repositoryClient

println("** Building files mapped to ${this.class.getName()}.groovy script")

// verify required build properties
buildUtils.assertBuildProperties(props.latx_requiredBuildProperties)

// create language datasets
def langQualifier = "latx"
buildUtils.createLanguageDatasets(langQualifier)

// iterate through build list
sortedList.each { buildFile ->
	println "*** Building file $buildFile"

    String cpyDir = props.getFileProperty('latx_cpyDir', buildFile)

	// copy build file and dependency files to data sets
	lettresATXUtils.copySourceFiles(buildFile, props.latx_srcPDS, props.latx_cpyPDS, cpyDir)

	// create mvs commands
	String member = CopyToPDS.createMemberName(buildFile)
	File logFile = new File("${props.buildOutDir}/${member}.log")
	if (logFile.exists())
		logFile.delete()

    File syntaxlogFile = new File("${props.buildOutDir}/${member}_syntax.log")
	if (logFile.exists())
		logFile.delete()

	File assemblyLogFile = new File("${props.buildOutDir}/${member}_assembly.log")
	if (logFile.exists())
		logFile.delete()

    File linkEditLogFile = new File("${props.buildOutDir}/${member}_linkedit.log")
	if (logFile.exists())
		logFile.delete()

    // one per step in the original procedure
	MVSExec rechercheCopies = createRechercheCopiesCommand(buildFile, member, logFile)
    MVSExec obtentionCopies = createObtentionCopiesCommand(buildFile, member, logFile)
	MVSExec insertionCopies = createInsertionCopiesCommand(buildFile, member, logFile)
	MVSExec recopieSource = createRecopieSourceCommand(buildFile, member, logFile)
    MVSExec controleSyntaxe = createControleSyntaxeCommand(buildFile, member, syntaxlogFile)
    MVSExec compositionTable = createCompositionTableCommand(buildFile, member)
    MVSExec assemblage = createAssemblageCommand(buildFile, member, assemblyLogFile)
	MVSExec linkedit = createLinkeditCommand(buildFile, member, linkEditLogFile)

    // execute mvs commands in a mvs job
	MVSJob job = new MVSJob()
	job.start()

    // Recherche les copies
    int rc = rechercheCopies.execute()
    boolean continueFlag = True

    // If copies found
    if(rc == 0) { 
        rc = obtentionCopies.execute.execute() 
        if (rc == 0){
            int rc = insertionCopies.execute()            
        }
        else{
            String errorMsg = "*! The copies insertion return code ($rc) for $buildFile exceeded the maximum return code allowed (0)"
		    println(errorMsg)
		    props.error = "True"
		    buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile],client:getRepositoryClient())
            continueFlag = False
        }
    }
    // no copies found
    else if(rc == 1) { 
        rc = recopieSource.execute() 
        if (rc > 0){ 
            String errorMsg = "*! The source copy return code ($rc) for $buildFile exceeded the maximum return code allowed (0)"
		    println(errorMsg)
		    props.error = "True"
		    buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile],client:getRepositoryClient())
            continueFlag = False      
        }
    }
    else { 
        String errorMsg = "*! The scanner return code ($rc) for $buildFile exceeded the maximum return code allowed (1)"
		println(errorMsg)
		props.error = "True"
		buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile],client:getRepositoryClient())
        continueFlag = False          
	}

    if(continueFlag){
        rc = controleSyntaxe.execute() 
        if(rc > 4){   
            String errorMsg = "*! The syntax check return code ($rc) for $buildFile exceeded the maximum return code allowed (4)"
		    println(errorMsg)
		    props.error = "True"
		    buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}_syntax.log":syntaxlogFile],client:getRepositoryClient())
            continueFlag = False           
        }
    }

    if(continueFlag){
        rc = compositionTable.execute() 
        if(rc > 0){
            String errorMsg = "*! The table composition return code ($rc) for $buildFile exceeded the maximum return code allowed (0)"
		    println(errorMsg)
		    props.error = "True"
		    buildUtils.updateBuildResult(errorMsg:errorMsg,client:getRepositoryClient())
            continueFlag = False                   
        }
    }

    if(continueFlag){
        rc = assemblage.execute() 
        if(rc > 0){
            String errorMsg = "*! The assembly return code ($rc) for $buildFile exceeded the maximum return code allowed (0)"
		    println(errorMsg)
		    props.error = "True"
		    buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}_assembly.log":assemblyLogFile],client:getRepositoryClient())
            continueFlag = False                 
        }
    }

    if(continueFlag){
        rc = linkedit.execute() 
        if(rc > 8){
            String errorMsg = "*! The linkEdit return code ($rc) for $buildFile exceeded the maximum return code allowed (8)"
		    println(errorMsg)
		    props.error = "True"
		    buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}_linkedit.log":linkEditLogFile],client:getRepositoryClient())
            continueFlag = False              
        }
    }

    // clean up passed DD statements
	job.stop()
}

def createRechercheCopiesCommand(String buildFile, String member, File logFile) {
	
		// define the MVSExec command to compile the program
		MVSExec scan = new MVSExec().file(buildFile).pgm("PCPY05")
	
		scan.dd(new DDStatement().name("PCPY0501").dsn("$latx_srcPDS($member)").options('shr'))
		scan.dd(new DDStatement().name("PCPY0502").dsn("&&WORK1").options(props.latx_work1TempOptions).pass(True))
		scan.dd(new DDStatement().name("SYSOUT").output(True)

		// add a copy command to the compile command to copy the SYSPRINT from the temporary dataset to an HFS log file
		scan.copy(new CopyToHFS().ddName("SYSOUT").file(logFile).hfsEncoding(props.logEncoding)).append(True)

		
		return scan
}

def createObtentionCopiesCommand(String buildFile, String member, File logFile) {
	
		// define the MVSExec command to compile the program
		MVSExec create = new MVSExec().file(buildFile).pgm("IEBPTPCH")
	
		create.dd(new DDStatement().name("SYSUT1").dsn("$props.latx_cpyPDS").options('shr'))
		create.dd(new DDStatement().name("SYSUT2").dsn("&&WORK2").options(props.latx_work2TempOptions).pass(True))
		create.dd(new DDStatement().name("SYSIN").dsn("&&WORK1").options('old,delete'))
		
		create.dd(new DDStatement().name("SYSOUT").output(True)
		create.dd(new DDStatement().name("SYSUDUMP").output(True)
		create.dd(new DDStatement().name("SYSPRINT").output(True)
	
		// add a copy command to the compile command to copy the SYSOUT,SYSDUMP and SYSPRINT from the temporary datasets to an HFS log file
		create.copy(new CopyToHFS().ddName("SYSOUT").file(logFile).hfsEncoding(props.logEncoding)).append(True)
		create.copy(new CopyToHFS().ddName("SYSUDUMP").file(logFile).hfsEncoding(props.logEncoding)).append(True)
		create.copy(new CopyToHFS().ddName("SYSPRINT").file(logFile).hfsEncoding(props.logEncoding)).append(True)
	
		return create
}

def createInsertionCopiesCommand(String buildFile, String member, File logFile) {
	
		// define the MVSExec command to compile the program
		MVSExec insert = new MVSExec().file(buildFile).pgm("PCPY06")

		insert.dd(new DDStatement().name("SYSOUT").output(True)
		insert.dd(new DDStatement().name("PCPY0601").dsn("$props.latx_srcPDS($member)").options('shr'))
		insert.dd(new DDStatement().name("PCPY0602").dsn("&&WORK2").options('old,delete'))
		insert.dd(new DDStatement().name("PCPY0603").dsn("&&WORK3").options(props.latx_work3TempOptions).pass(True))
	
		// add a copy command to the compile command to copy the SYSOUT from the temporary dataset to an HFS log file
		insert.copy(new CopyToHFS().ddName("SYSOUT").file(logFile).hfsEncoding(props.logEncoding)).append(True)
		
		return insert
}

def createRecopieSourceCommand(String buildFile, String member, File logFile) {
	
		// define the MVSExec command to compile the program
		MVSExec recopy = new MVSExec().file(buildFile).pgm("IEBGENER")

		recopy.dd(new DDStatement().name("SYSPRINT").output(True)
		recopy.dd(new DDStatement().name("SYSUT1").dsn("$props.latx_srcPDS($member)").options('shr'))
		recopy.dd(new DDStatement().name("SYSUT2").dsn("&&WORK3").options(props.latx_work3TempOptions).pass(True))
	
        recopy.DD(new DDStatement().name("SYSIN").options("dummy blksize(80)")

		// add a copy command to the compile command to copy the SYSPRINT from the temporary dataset to an HFS log file
		recopy.copy(new CopyToHFS().ddName("SYSPRINT").file(logFile).hfsEncoding(props.logEncoding)).append(True)
		
		return recopy
}

def createCompositionTableCommand(String buildFile, String member) {

		def parms = props.getFileProperty('latx_tableCreationParms', buildFile) ?: ""
	
		// define the MVSExec command to compile the program
		MVSExec table = new MVSExec().file(buildFile).pgm("PTXT02").parm(parms)

		table.dd(new DDStatement().name("PTXT0201").dsn("&&WORK3").options('shr'))
		table.dd(new DDStatement().name("PTXT0202").dsn("&&WK1").options(props.latx_wk1TempOptions).pass(True))
		
		return table
}

def createControleSyntaxeCommand(String buildFile, String member, File logFile) {
	
		// define the MVSExec command to compile the program
		MVSExec syntaxe = new MVSExec().file(buildFile).pgm("PATX00")
		
		syntaxe.dd(new DDStatement().name("STEPLIB").dsn("$props.latx_syntaxe_steplib").options('shr'))

		syntaxe.dd(new DDStatement().name("PATX0001").dsn("&&WORK3").options('shr'))

		syntaxe.dd(new DDStatement().name("PATX0002").output(True))
		syntaxe.dd(new DDStatement().name("SYSOUT").output(True)
		syntaxe.dd(new DDStatement().name("SYSUDUMP").output(True)
		syntaxe.dd(new DDStatement().name("CEEDUMP").output(True)
		
		// add a copy command to the compile command to copy the SYSOUT,SYSDUMP and CEEDUMP from the temporary datasets to an HFS log file
		syntaxe.copy(new CopyToHFS().ddName("SYSOUT").file(logFile).hfsEncoding(props.logEncoding)).append(True)
		syntaxe.copy(new CopyToHFS().ddName("SYSUDUMP").file(logFile).hfsEncoding(props.logEncoding)).append(True)
		syntaxe.copy(new CopyToHFS().ddName("CEEDUMP").file(logFile).hfsEncoding(props.logEncoding)).append(True)
		syntaxe.copy(new CopyToHFS().ddName("PATX0002").file(logFile).hfsEncoding(props.logEncoding)).append(True)
		
		return syntaxe
}

def createAssemblageCommand(String buildFile, String member, File logFile) {
	
		def parms = props.getFileProperty('latx_assemblyParms', buildFile) ?: ""
	
		// define the MVSExec command to compile the program
		MVSExec assemblage = new MVSExec().file(buildFile).pgm("ASMA90").parm(parms)
		
		assemblage.dd(new DDStatement().name("SYSLIN").dsn("&&OBJSET").options(props.latx_objsTempOptions).pass(True))
		assemblage.dd(new DDStatement().name("SYSIN").dsn("&&WK1").options('old,delete'))
		assemblage.dd(new DDStatement().name("SYSLIB").dsn("$props.MACLIB").options('shr'))

		assemblage.dd(new DDStatement().name("SYSUT1").dsn("&&SYSUT1").options(props.latx_assemblageSysutAssemblyTempOptions).pass(True))
		assemblage.dd(new DDStatement().name("SYSUT2").dsn("&&SYSUT2").options(props.latx_assemblageSysutAssemblyTempOptions).pass(True))
		assemblage.dd(new DDStatement().name("SYSUT3").dsn("&&SYSUT3").options(props.latx_assemblageSysutAssemblyTempOptions).pass(True))
		
        assemblage.dd(new DDStatement().name("SYSPRINT").output(True)
		assemblage.copy(new CopyToHFS().ddName("SYSPRINT").file(logFile).hfsEncoding(props.logEncoding)).append(True) 

		return assemblage
}

def createLinkeditCommand(String buildFile, String member, File logFile) {
	
		def parms = props.getFileProperty('latx_linkeditParms', buildFile) ?: ""
	
		// define the MVSExec command to compile the program
		MVSExec linked = new MVSExec().file(buildFile).pgm("IEWL").parm(parms)
		
		linked.dd(new DDStatement().name("SYSLIN").dsn("&&OBJSET").options('old,delete'))

		linked.dd(new DDStatement().name("SYSUT1").dsn("&&SYSUT1").options(props.latx_linkeditSysutTempOptions))
		linked.dd(new DDStatement().name("SYSLMOD").dsn("$props.latx_loadPDS($member)").options('shr'))
		linked.DD(new DDStatement().name("SYSIN").options("dummy")
        linked.DD(new DDStatement().name("SYSLIB").options("dummy blksize(80)")

        linked.dd(new DDStatement().name("SYSPRINT").output(True)
		linked.copy(new CopyToHFS().ddName("SYSPRINT").file(logFile).hfsEncoding(props.logEncoding)).append(True) 

		return linked
}