// Global variables

// Agents labels
def linuxAgent = 'master'
def agentLabel = 'zOS-Agent'

// Verbose
def buildVerbose = ''

// GIT
def gitCred = 'Git'
def gitHost = '172.26.86.20'

// Application Name
def application = 'SAM'

def srcGitRepo =   'git@'+gitHost+':'+application+'.git'
def srcGitBranch = 'master'
	
// Build type
//  -i: incremental
//  -f: full
//  -c: only changed source
def buildType='-f'

// Build extra args
//  -d: COBOL debug options
def buildExtraParams='-d'

pipeline {

	agent { label linuxAgent }

	environment { WORK_DIR = "${WORKSPACE}/BUILD-${BUILD_NUMBER}" }

	options { skipDefaultCheckout(true) }

	stages {
		
		stage('Git Clone/Refresh') {
			agent { label agentLabel }
			steps {
				script {
					 dir(application) {
                            buildType='-f'
                            scmVars = checkout([$class: 'GitSCM', branches: [[name: srcGitBranch]], 
                                                doGenerateSubmoduleConfigurations: false, 
                                                submoduleCfg: [],								                    
                                                userRemoteConfigs: [[
                                                                        credentialsId: gitCred,
                                                                        url: srcGitRepo,
                                                                        ]]])
					 }
					
				}
			}
		}
		stage('DBB Build') {
			steps {
				script{
					node( agentLabel ) {					
						sh "groovyz ${WORKSPACE}/genapp/zAppBuild/build.groovy --logEncoding UTF-8 -w ${WORKSPACE} --application ${application} --sourceDir ${WORKSPACE} --workDir ${WORKSPACE}/BUILD-${BUILD_NUMBER}  --hlq ${dbbHlq}.DBB ${buildType} ${buildVerbose} ${buildExtraParams}"					
 					}
				}
			}
			post {
				always {
					node( agentLabel ) {
						dir("${WORKSPACE}/BUILD-${BUILD_NUMBER}") {
							archiveArtifacts allowEmptyArchive: true,
											artifacts: '*.log,*.json,*.html',
											excludes: '*clist',
											onlyIfSuccessful: false
						}
					}
				}
			}
		}


	}
}
