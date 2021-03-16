// Global variables

// Agents labels
def linuxAgent = 'master'
def agentLabel = 'zOSSlaveJ'

// Verbose
def verbose = false
def buildVerbose = ''

// Hosts and ports
def linuxHost = '10.1.1.1'
def zosHost = '10.1.1.2'
def zosPort = '22'

// DBB
def dbbUrl = 'https://'+linuxHost+':11043/dbb'
def dbbHlq = 'JENKINS'
def dbbDaemonPort = '18181'
def dbbGroovyzOpts= ''

// GIT
def gitCredId = 'gitlab_ssh_cred'
def gitCred = 'gitlab_zos_cred'
def gitOrg = 'gitlab1'
def gitHost = linuxHost

def srcGitRepo =   'gitlab@'+gitHost+':'+gitOrg+'/genapp.git'
def srcGitBranch = 'master'
	
// Build type
//  -i: incremental
//  -f: full
//  -c: only changed source
def buildType='-f'

// Build extra args
//  -d: COBOL debug options
def buildExtraParams='-d'
	
// code coverage daemon port
def ccPORT='8005'
def CCDIR='/Appliance/IDz14.2.1'

def isMergeRequest ( ) {
	def isMr = env.CHANGE_ID != null || ( env.gitlabActionType != null && env.gitlabActionType == 'MERGE' )
	if ( isMr && env.CHANGE_ID == null )
		env.CHANGE_ID = env.gitlabMergeRequestIid
	return isMr		
}

pipeline {

	agent { label linuxAgent }

	environment { WORK_DIR = "${WORKSPACE}/BUILD-${BUILD_NUMBER}" }

	options { skipDefaultCheckout(true) }

	stages {
	
		stage('Init') {
			steps {
				script {
					env.DBB_HOME = '/var/dbb/1.0.6'
					echo "branch: ${env.BRANCH_NAME}"
					if ( env.ZOS_HOST ) {
						zosHost = env.ZOS_HOST
					} else {
						env.ZOS_HOST = zosHost					
					}
					if ( env.ZOS_PORT ) {
						zosPort = env.ZOS_PORT
					} else {
						env.ZOS_PORT = zosPort						
					} 				
					if ( env.BRANCH_NAME != null ) {
						srcGitBranch = env.BRANCH_NAME;
						if ( isMergeRequest () ) {
							dbbHlq = dbbHlq + ".PR"
						} else {
							// notes: Branch must respect z/Os naming conventions for now
							dbbHlq = dbbHlq + "." + srcGitBranch.toUpperCase()
						}
					}
					if ( env.DEBUG_PIPELINE && env.DEBUG_PIPELINE == 'true' ) {
						verbose = true
						buildVerbose = '-v'
						echo sh(script: 'env|sort', returnStdout: true)						
					} 					
				}
			}
		}
		
		stage('Git Clone/Refresh') {
			agent { label agentLabel }
			steps {
				script {
					 dir('genapp') {
						 if ( isMergeRequest ( ) ) {
								// This is a merge request
								def gitMrBranch = "merge-requests/${env.CHANGE_ID}" 
								def gitRefspecBranch =
								 		"+refs/merge-requests/${env.CHANGE_ID}/head:refs/remotes/merge-requests/${env.CHANGE_ID} " +
									    "+refs/merge-requests/${env.CHANGE_ID}/head:refs/remotes/origin/merge-requests/${env.CHANGE_ID} "
								scmVars = checkout([$class: 'GitSCM',
									branches: [[name: gitMrBranch ]],
									doGenerateSubmoduleConfigurations: false,
								    extensions: [
								    			[$class: 'SparseCheckoutPaths',  sparseCheckoutPaths:[
			                                 		[$class:'SparseCheckoutPath', path:'cobol/'],
			                                 		[$class:'SparseCheckoutPath', path:'copybook/'],
			                                 		[$class:'SparseCheckoutPath', path:'application-conf/'],
			                                 		[$class:'SparseCheckoutPath', path:'zAppBuild/'],
			                                 		[$class:'SparseCheckoutPath', path:'testcases/']
		                                 		]]
								                ],								
									userRemoteConfigs: [
										[refspec:gitRefspecBranch ,
											url: srcGitRepo, credentialsId: gitCred,
											]]
								])
							} else {
								buildType='-f'
								scmVars = checkout([$class: 'GitSCM', branches: [[name: srcGitBranch]], 
								                    doGenerateSubmoduleConfigurations: false, 
								                    submoduleCfg: [],
								                    extensions: [
								                                 [$class: 'SparseCheckoutPaths',  sparseCheckoutPaths:[
								                                 		[$class:'SparseCheckoutPath', path:'cobol/'],
								                                 		[$class:'SparseCheckoutPath', path:'copybook/'],
								                                 		[$class:'SparseCheckoutPath', path:'application-conf/'],
								                                 		[$class:'SparseCheckoutPath', path:'zAppBuild/'],
								                                 		[$class:'SparseCheckoutPath', path:'testcases/']
								                                 		]]
								                                		 ],								                    
								                    userRemoteConfigs: [[
								                    				     credentialsId: gitCred,
								                                         url: srcGitRepo,
								                                         ]]])

							}

					 }
					
				}
			}
		}
		stage('DBB Build') {
			steps {
				script{
					node( agentLabel ) {
						if ( dbbDaemonPort != null ) {
							def r = sh script: "netstat | grep ${dbbDaemonPort}", returnStatus: true
							if ( r == 0 ) {
								println "DBB Daemon is running.."
								dbbGroovyzOpts = "-DBB_DAEMON_PORT ${dbbDaemonPort} -DBB_DAEMON_HOST 127.0.0.1"
							}
							else {
								println "WARNING: DBB Daemon not running build will be longer.."
							//	currentBuild.result = "UNSTABLE"
							}
						}
						
						sh "$DBB_HOME/bin/groovyz ${WORKSPACE}/genapp/zAppBuild/build.groovy --logEncoding UTF-8 -w ${WORKSPACE} --application genapp --sourceDir ${WORKSPACE}  --workDir ${WORKSPACE}/BUILD-${BUILD_NUMBER}  --hlq ${dbbHlq}.GENAPP --url $dbbUrl -pw ADMIN $buildType  $buildVerbose $buildExtraParams "					
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
	
		

post {
		success {
			updateGitlabCommitStatus(name: "Jenkins Job: '${env.JOB_NAME} [${env.BUILD_NUMBER} - ${env.BUILD_DISPLAY_NAME}]' (${env.BUILD_URL})", state: 'success')
		}
		unstable {
			updateGitlabCommitStatus(name: "Jenkins Job: '${env.JOB_NAME} [${env.BUILD_NUMBER} - ${env.BUILD_DISPLAY_NAME}]' (${env.BUILD_URL})", state: 'success')
		}		
		failure {
			updateGitlabCommitStatus(name: "Jenkins Job: '${env.JOB_NAME} [${env.BUILD_NUMBER} - ${env.BUILD_DISPLAY_NAME}]' (${env.BUILD_URL})", state: 'failed')
		}
        aborted {
			updateGitlabCommitStatus(name: "Jenkins Job: '${env.JOB_NAME} [${env.BUILD_NUMBER} - ${env.BUILD_DISPLAY_NAME}]' (${env.BUILD_URL})", state: 'canceled')
        }		
	}	
}
