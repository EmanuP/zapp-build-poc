@groovy.transform.BaseScript com.ibm.dbb.groovy.ScriptLoader baseScript
import com.ibm.dbb.repository.*
import com.ibm.dbb.dependency.*
import com.ibm.dbb.build.*
import groovy.transform.*
import com.ibm.jzos.ZFile

// define script properties
@Field BuildProperties props = BuildProperties.getInstance()
@Field def buildUtils= loadScript(new File("${props.zAppBuildDir}/utilities/BuildUtilities.groovy"))
@Field def lettresUtils= loadScript(new File("${props.zAppBuildDir}/utilities/LettresUtilities.groovy"))
@Field RepositoryClient repositoryClient

println("** Building files mapped to ${this.class.getName()}.groovy script")

// verify required build properties
buildUtils.assertBuildProperties(props.lettres_requiredBuildProperties)

// create language datasets
def langQualifier = "lettres"
buildUtils.createLanguageDatasets(langQualifier)

// iterate through build list
argMap.buildList.each { buildFile ->
	println "*** Building file $buildFile"

    String cpyDir = props.getFileProperty('lettres_cpyDir', buildFile)

	// copy build file and dependency files to data sets
	lettresUtils.copySourceFiles(buildFile, props.lettres_srcPDS, props.lettres_cpyPDS, cpyDir)

	// create mvs commands
	String member = CopyToPDS.createMemberName(buildFile)
	File logFile = new File("${props.buildOutDir}/${member}.log")
	if (logFile.exists())
		logFile.delete()

	File tableLogFile = new File("${props.buildOutDir}/${member}_table.log")
	if (tableLogFile.exists())
		tableLogFile.delete()

    File syntaxLogFile = new File("${props.buildOutDir}/${member}_syntax.log")
	if (syntaxLogFile.exists())
		syntaxLogFile.delete()

	File assemblyLogFile = new File("${props.buildOutDir}/${member}_assembly.log")
	if (assemblyLogFile.exists())
		assemblyLogFile.delete()

    File linkEditLogFile = new File("${props.buildOutDir}/${member}_linkedit.log")
	if (linkEditLogFile.exists())
		linkEditLogFile.delete()

    // one per step in the original procedure
	MVSExec rechercheCopies = createRechercheCopiesCommand(buildFile, member, logFile)
    MVSExec obtentionCopies = createObtentionCopiesCommand(buildFile, member, logFile)
	MVSExec insertionCopies = createInsertionCopiesCommand(buildFile, member, logFile)
	MVSExec recopieSource = createRecopieSourceCommand(buildFile, member, logFile)
	MVSExec controleSyntaxe = null

	if(props.getFileProperty('letter_type', buildFile).equals('ATX')){
    	controleSyntaxe = createControleSyntaxeATXCommand(buildFile, member, syntaxLogFile)
	}else if(props.getFileProperty('letter_type', buildFile).equals('HTML')){
    	controleSyntaxe = createControleSyntaxeHTMLCommand(buildFile, member, syntaxLogFile)
	}

    MVSExec compositionTable = createCompositionTableCommand(buildFile, member, tableLogFile)
    MVSExec assemblage = createAssemblageCommand(buildFile, member, assemblyLogFile)
	MVSExec linkedit = createLinkeditCommand(buildFile, member, linkEditLogFile)

    // execute mvs commands in a mvs job
	MVSJob job = new MVSJob()
	job.start()

    // Recherche les copies
    int rc = rechercheCopies.execute()
    boolean continueFlag = true

    // If copies found
    if(rc == 0) { 
        rc = obtentionCopies.execute() 
        if (rc == 0){
            rc = insertionCopies.execute()            
        }
        else{
            String errorMsg = "*! The copies insertion return code ($rc) for $buildFile exceeded the maximum return code allowed (0)"
		    println(errorMsg)
		    props.error = "true"
		    buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile],client:getRepositoryClient())
            continueFlag = false
        }
    }
    // no copies found
    else if(rc == 1) { 
        rc = recopieSource.execute() 
        if (rc > 0){ 
            String errorMsg = "*! The source copy return code ($rc) for $buildFile exceeded the maximum return code allowed (0)"
		    println(errorMsg)
		    props.error = "true"
		    buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile],client:getRepositoryClient())
            continueFlag = false      
        }
    }
    else { 
        String errorMsg = "*! The scanner return code ($rc) for $buildFile exceeded the maximum return code allowed (1)"
		println(errorMsg)
		props.error = "true"
		buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}.log":logFile],client:getRepositoryClient())
        continueFlag = false          
	}

    if(continueFlag){
        rc = controleSyntaxe.execute() 
        if(rc > 4){   
            String errorMsg = "*! The syntax check return code ($rc) for $buildFile exceeded the maximum return code allowed (4)"
		    println(errorMsg)
		    props.error = "true"
		    buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}_syntax.log":syntaxLogFile],client:getRepositoryClient())
            continueFlag = false           
        }
    }

    if(continueFlag){
        rc = compositionTable.execute() 
        if(rc > 0){
            String errorMsg = "*! The table composition return code ($rc) for $buildFile exceeded the maximum return code allowed (0)"
		    println(errorMsg)
		    props.error = "true"
		    buildUtils.updateBuildResult(errorMsg:errorMsg,client:getRepositoryClient())
            continueFlag = false                   
        }
    }

    if(continueFlag){
        rc = assemblage.execute() 
        if(rc > 0){
            String errorMsg = "*! The assembly return code ($rc) for $buildFile exceeded the maximum return code allowed (0)"
		    println(errorMsg)
		    props.error = "true"
		    buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}_assembly.log":assemblyLogFile],client:getRepositoryClient())
            continueFlag = false                 
        }
    }

    if(continueFlag){
        rc = linkedit.execute() 
        if(rc > 8){
            String errorMsg = "*! The linkEdit return code ($rc) for $buildFile exceeded the maximum return code allowed (8)"
		    println(errorMsg)
		    props.error = "true"
		    buildUtils.updateBuildResult(errorMsg:errorMsg,logs:["${member}_linkedit.log":linkEditLogFile],client:getRepositoryClient())
            continueFlag = false              
        }
    }

    // clean up passed DD statements
	job.stop()
}

def createRechercheCopiesCommand(String buildFile, String member, File logFile) {
	
		// define the MVSExec command to compile the program
		MVSExec scan = new MVSExec().file(buildFile).pgm("PCPY05")
	
		scan.dd(new DDStatement().name("PCPY0501").dsn("$props.lettres_srcPDS($member)").options('shr'))
		scan.dd(new DDStatement().name("PCPY0502").dsn("&&WORK1").options(props.lettres_work1TempOptions).pass(true))
		scan.dd(new DDStatement().name("SYSOUT").output(true))

		// add a copy command to the compile command to copy the SYSPRINT from the temporary dataset to an HFS log file
		scan.copy(new CopyToHFS().ddName("SYSOUT").file(logFile).hfsEncoding(props.logEncoding).append(true))

		
		return scan
}

def createObtentionCopiesCommand(String buildFile, String member, File logFile) {
	
		// define the MVSExec command to compile the program
		MVSExec create = new MVSExec().file(buildFile).pgm("IEBPTPCH")
	
		create.dd(new DDStatement().name("SYSUT1").dsn("$props.lettres_cpyPDS").options('shr'))
		create.dd(new DDStatement().name("SYSUT2").dsn("&&WORK2").options(props.lettres_work2TempOptions).pass(true))
		create.dd(new DDStatement().name("SYSIN").dsn("&&WORK1").options('old'))
		
		create.dd(new DDStatement().name("SYSOUT").output(true))
		create.dd(new DDStatement().name("SYSUDUMP").output(true))
		create.dd(new DDStatement().name("SYSPRINT").output(true))
	
		// add a copy command to the compile command to copy the SYSOUT,SYSDUMP and SYSPRINT from the temporary datasets to an HFS log file
		create.copy(new CopyToHFS().ddName("SYSOUT").file(logFile).hfsEncoding(props.logEncoding).append(true))
		create.copy(new CopyToHFS().ddName("SYSUDUMP").file(logFile).hfsEncoding(props.logEncoding).append(true))
		create.copy(new CopyToHFS().ddName("SYSPRINT").file(logFile).hfsEncoding(props.logEncoding).append(true))
	
		return create
}

def createInsertionCopiesCommand(String buildFile, String member, File logFile) {
	
		// define the MVSExec command to compile the program
		MVSExec insert = new MVSExec().file(buildFile).pgm("PCPY06")

		insert.dd(new DDStatement().name("SYSOUT").output(true))
		insert.dd(new DDStatement().name("PCPY0601").dsn("$props.lettres_srcPDS($member)").options('shr'))
		insert.dd(new DDStatement().name("PCPY0602").dsn("&&WORK2").options('old'))
		insert.dd(new DDStatement().name("PCPY0603").dsn("&&WORK3").options(props.lettres_work3TempOptions).pass(true))
	
		// add a copy command to the compile command to copy the SYSOUT from the temporary dataset to an HFS log file
		insert.copy(new CopyToHFS().ddName("SYSOUT").file(logFile).hfsEncoding(props.logEncoding).append(true))
		
		return insert
}

def createRecopieSourceCommand(String buildFile, String member, File logFile) {
	
		// define the MVSExec command to compile the program
		MVSExec recopy = new MVSExec().file(buildFile).pgm("IEBGENER")

		recopy.dd(new DDStatement().name("SYSPRINT").output(true))
		recopy.dd(new DDStatement().name("SYSUT1").dsn("$props.lettres_srcPDS($member)").options('shr'))
		recopy.dd(new DDStatement().name("SYSUT2").dsn("&&WORK3").options(props.lettres_work3TempOptions).pass(true))
	
        recopy.dd(new DDStatement().name("SYSIN").options("dummy blksize(80)"))

		// add a copy command to the compile command to copy the SYSPRINT from the temporary dataset to an HFS log file
		recopy.copy(new CopyToHFS().ddName("SYSPRINT").file(logFile).hfsEncoding(props.logEncoding).append(true))
		
		return recopy
}

def createCompositionTableCommand(String buildFile, String member, File logFile) {

		def parms = props.getFileProperty('lettres_tableCreationParms', buildFile) ?: ""
	
		// define the MVSExec command to compile the program
		MVSExec table = new MVSExec().file(buildFile).pgm("PTXT02").parm(parms)

		table.dd(new DDStatement().name("PTXT0201").dsn("&&WORK3").options('shr'))
		table.dd(new DDStatement().name("PTXT0202").dsn("&&WK1").options(props.lettres_wk1TempOptions).pass(true))
		table.dd(new DDStatement().name("SYSOUT").output(true))

		// add a copy command to the compile command to copy the SYSOUT,SYSDUMP and CEEDUMP from the temporary datasets to an HFS log file
		table.copy(new CopyToHFS().ddName("SYSOUT").file(logFile).hfsEncoding(props.logEncoding).append(true))		
		
		return table
}

def createControleSyntaxeATXCommand(String buildFile, String member, File logFile) {
	
		// define the MVSExec command to compile the program
		MVSExec syntaxe = new MVSExec().file(buildFile).pgm("PATX00")

		syntaxe.dd(new DDStatement().name("PATX0001").dsn("&&WORK3").options('shr').pass(true))

		syntaxe.dd(new DDStatement().name("PATX0002").output(true))
		syntaxe.dd(new DDStatement().name("SYSOUT").output(true))
		syntaxe.dd(new DDStatement().name("SYSUDUMP").output(true))
		syntaxe.dd(new DDStatement().name("CEEDUMP").output(true))
		
		// add a copy command to the compile command to copy the SYSOUT,SYSDUMP and CEEDUMP from the temporary datasets to an HFS log file
		syntaxe.copy(new CopyToHFS().ddName("SYSOUT").file(logFile).hfsEncoding(props.logEncoding).append(true))
		syntaxe.copy(new CopyToHFS().ddName("SYSUDUMP").file(logFile).hfsEncoding(props.logEncoding).append(true))
		syntaxe.copy(new CopyToHFS().ddName("CEEDUMP").file(logFile).hfsEncoding(props.logEncoding).append(true))
		syntaxe.copy(new CopyToHFS().ddName("PATX0002").file(logFile).hfsEncoding(props.logEncoding).append(true))
		
		return syntaxe
}

def createControleSyntaxeHTMLCommand(String buildFile, String member, File logFile) {
	
		// define the MVSExec command to compile the program
		MVSExec syntaxe = new MVSExec().file(buildFile).pgm("PHTM00")

		syntaxe.dd(new DDStatement().name("PHTM0001").dsn("&&WORK3").options('shr').pass(true))

		syntaxe.dd(new DDStatement().name("PHTM0002").output(true))
		syntaxe.dd(new DDStatement().name("PHTM0003").output(true))
		syntaxe.dd(new DDStatement().name("SYSOUT").output(true))
		syntaxe.dd(new DDStatement().name("SYSUDUMP").output(true))
		syntaxe.dd(new DDStatement().name("CEEDUMP").output(true))
		
		// add a copy command to the compile command to copy the SYSOUT,SYSDUMP and CEEDUMP from the temporary datasets to an HFS log file
		syntaxe.copy(new CopyToHFS().ddName("SYSOUT").file(logFile).hfsEncoding(props.logEncoding).append(true))
		syntaxe.copy(new CopyToHFS().ddName("SYSUDUMP").file(logFile).hfsEncoding(props.logEncoding).append(true))
		syntaxe.copy(new CopyToHFS().ddName("CEEDUMP").file(logFile).hfsEncoding(props.logEncoding).append(true))
		syntaxe.copy(new CopyToHFS().ddName("PHTM0002").file(logFile).hfsEncoding(props.logEncoding).append(true))
		syntaxe.copy(new CopyToHFS().ddName("PHTM0003").file(logFile).hfsEncoding(props.logEncoding).append(true))
		
		return syntaxe
}

def createAssemblageCommand(String buildFile, String member, File logFile) {
	
		def parms = props.getFileProperty('lettres_assemblyParms', buildFile) ?: ""
	
		// define the MVSExec command to compile the program
		MVSExec assemblage = new MVSExec().file(buildFile).pgm("ASMA90").parm(parms)
		
		assemblage.dd(new DDStatement().name("SYSLIN").dsn("${props.lettres_objPDS}($member)").options('shr').output(true))
		assemblage.dd(new DDStatement().name("SYSIN").dsn("&&WK1").options('old'))
		assemblage.dd(new DDStatement().name("SYSLIB").dsn("$props.MACLIB").options('shr'))
		if (props.lettres_macroPDS && ZFile.dsExists("'${props.lettres_macroPDS}'"))
			assemblage.dd(new DDStatement().dsn(props.lettres_macroPDS).options("shr"))

		assemblage.dd(new DDStatement().name("SYSUT1").dsn("&&SYSUT1").options(props.lettres_assemblageSysutAssemblyTempOptions))
		
        assemblage.dd(new DDStatement().name("SYSPRINT").output(true))
		assemblage.copy(new CopyToHFS().ddName("SYSPRINT").file(logFile).hfsEncoding(props.logEncoding).append(true)) 

		return assemblage
}

def createLinkeditCommand(String buildFile, String member, File logFile) {
	
		def parms = props.getFileProperty('lettres_linkeditParms', buildFile) ?: ""
	
		// define the MVSExec command to compile the program
		MVSExec linked = new MVSExec().file(buildFile).pgm("IEWL").parm(parms)

		linked.dd(new DDStatement().name("SYSLIN").dsn("${props.lettres_objPDS}($member)").options('shr'))

		linked.dd(new DDStatement().name("SYSUT1").dsn("&&SYSUT1").options(props.lettres_linkeditSysutTempOptions))
		linked.dd(new DDStatement().name("SYSLMOD").dsn("$props.lettres_loadPDS($member)").options('shr'))
		linked.dd(new DDStatement().name("SYSIN").options("dummy"))
        linked.dd(new DDStatement().name("SYSLIB").options("dummy blksize(80)"))

        linked.dd(new DDStatement().name("SYSPRINT").output(true))
		linked.copy(new CopyToHFS().ddName("SYSPRINT").file(logFile).hfsEncoding(props.logEncoding).append(true))

		return linked
}

def getRepositoryClient() {
	if (!repositoryClient && props."dbb.RepositoryClient.url")
		repositoryClient = new RepositoryClient().forceSSLTrusted(true)

	return repositoryClient
}