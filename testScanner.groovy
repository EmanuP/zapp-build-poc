@groovy.transform.BaseScript com.ibm.dbb.groovy.ScriptLoader baseScript
import com.ibm.dbb.repository.*
import com.ibm.dbb.dependency.*
import com.ibm.dbb.build.*
import com.ibm.dbb.build.report.*
import com.ibm.dbb.build.html.*
import groovy.util.*
import groovy.transform.*
import groovy.time.*
import groovy.xml.*
import com.ibm.jzos.ZFile

@Field def lettreUtils= loadScript(new File("/u/emanup/workspace/zAppBuild/utilities/LettresUtilities.groovy"))

println("Scanning:  /u/emanup/workspace/log/scanList.txt")

File dependencyListFile = new File( "/u/emanup/workspace/log/scanList.txt")

List<String> dependenciesNames = lettreUtils.parseDependencyList(dependencyListFile)

dependenciesNames.each { name -> 
    println(name)
}