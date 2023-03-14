import static com.innovapost.jenkins.CommonPipelineUtils.PIPELINE_TYPES
import static com.innovapost.jenkins.CommonPipelineUtils.sendEmail
import static com.innovapost.jenkins.BuildPipelineUtils.*
import static com.innovapost.jenkins.CommonPipelineUtils.*

def call(String pollInterval) {
// have to define these here in order to set them in one script block
// and read them in a different one
    boolean appInitFilesRequired = false
    boolean runUnitTests = false
    boolean debugUnitTestsEnv = false
    boolean unitTestEnvDeleted = false
    JENKINS_URL = 'https://devcoe-jenkins-oss-prd.apps-npd-ocp.cpggpc.ca/'

    String depthOptionSVN = 'infinity'
    String NexusRepository = ''
    String cleanedBranchName 

    pollInterval = com.innovapost.jenkins.MendixPipelineUtils.setPollSCM(this, JENKINS_URL, pollInterval)

    // Check if stage is being retried
	script {
		retryStageCheck(this)
	}

    pipeline {
        // https://jenkins.io/doc/book/pipeline/syntax/
        options {
            buildDiscarder(logRotator(numToKeepStr: NUM_BUILDS_TO_KEEP, daysToKeepStr: NUM_OF_DAYS_BUILDS_TO_KEEP))
            disableConcurrentBuilds()
            timestamps() //timestamper plugin
            lock(label: 'pod', quantity: 2)
        }

        agent {
            // 'mendix' agent requires
            // 1. jenkins-mendix-slave docker image has been built
            //     (git.cpggpc.ca/scm/devcoe-jenkins/jenkins-mendix-slave.git)
            // 2. Kubernetes Pod Template added with label that matches
            //     (Manage Jenkins > Configure System)
            label 'jenkins-mendix-slave'
        }
        triggers {
            pollSCM pollInterval
        }

        environment {
            MENDIX_USER_APIKEY_CREDS = credentials('jenkins-ss-pipeline-svc-mx-userapikey')
            MENDIX_SVN_URL       = "https://teamserver.sprintr.com"
            // deployment platform parameters
            // added to support Mendix app unit test automation
            UNTEST_START_PATH    = "unittests/start"
            UNTEST_STATUS_PATH   = "unittests/status"
            UNTEST_REPORT_PATH   = "rest/unittestreportingservice/v1/resource/getReport"
            STATUS_TIMEOUT                 = 15
            STATUS_TIMEOUT_UNITS           = "MINUTES"
            FLOWTEST_SLEEP_TIME  = 2 // units are seconds
            // provisional credentials id; we're going to want to add a set of credentials for
            // accessing Mendix test endpoints
            MXTEST_CREDS         = credentials('jenkins-ss-pipeline-svc-mx-flowtest')
            //MENDIX_APP_ADMIN     = credentials('MENDIX_APP_ADMIN')
        }

        stages {
            stage ('read project properties'){
                steps{
                    script{
                        // Read Project properties that will be used later
                        envProps = readProperties file:"project.values"
                        // Read Build (App) properties that will be used later
                        buildProps = readProperties file:"build.values"
                        appInitFiles = buildProps['APPINITFOLDER']
                        unitTestEnv = buildProps['MENDIX_UnitTestsEnv']
                        debugUnitTestsEnv = buildProps['MENDIX_debugUnitTestsEnv']

                        if (appInitFiles) {
                            appInitFilesRequired = true
                            depthOptionSVN = 'infinity'
                            echo "Application Init Files Required in ${appInitFiles}"
                        }
                        if (unitTestEnv) {
                            runUnitTests = true
                            echo "Unit Tests Environment: ${unitTestEnv}"
                            envCreationJobName = "coo-acd/auto-env-creation/master"
                            def jobName = env.JOB_NAME.tokenize('/') as String[];
                            def repoName = jobName[1] + '-env';
                            deployJobName = "${envProps['ORGANIZATION']}-${envProps['PRODUCT']}/${repoName}/master"
                            echo "deployment Job Name: ${deployJobName}"
                        }
                    }
                }
            }
            stage('fetch source from SCM') {
                steps {
                    // Mendix tagging convention major-version.minor-version.patch.svn-revision, e.g. 1.2.12.102
                    script {
                    echo "${buildProps['MENDIX_APP_ID']} ${buildProps['MENDIX_APP_BRANCH']}"
                        // Jenkins declarative pipeline does not support the build-in SVN_REVISION variable, have to extract svn revision information from the SCM checkout processes
                        if (buildProps['MENDIX_APP_BRANCH'] == 'trunk') {
                            MENDIX_APP_BRANCH_REMOTE = ''
                        }else{
                            MENDIX_APP_BRANCH_REMOTE = 'branches/'
                        }
                    echo "SVNURL: ${MENDIX_SVN_URL}/${buildProps['MENDIX_APP_ID']}/${MENDIX_APP_BRANCH_REMOTE}${buildProps['MENDIX_APP_BRANCH']}"
                        dir ('svn'){
                            def MENDIX_SCM_VARS = checkout ([
                                    poll: false,
                                    scm: [
                                            $class: 'SubversionSCM',
                                            additionalCredentials: [],
                                            excludedCommitMessages: '',
                                            excludedRegions: '',
                                            excludedRevprop: '',
                                            excludedUsers: '',
                                            filterChangelog: false,
                                            ignoreDirPropChanges: false,
                                            includedRegions: '',
                                            locations: [[
                                                                credentialsId: 'jenkins-ss-pipeline-svc-mx-svn',
                                                                depthOption: "${depthOptionSVN}",
                                                                ignoreExternalsOption: true,
                                                                local: '../project',
                                                                remote: "${MENDIX_SVN_URL}/${buildProps['MENDIX_APP_ID']}/${MENDIX_APP_BRANCH_REMOTE}${buildProps['MENDIX_APP_BRANCH']}"
                                                        ]],
                                            quietOperation: true,
                                            workspaceUpdater: [$class: 'UpdateUpdater']
                                    ]
                                    // from Pipeline Syntax link, Snippet Generator, checkout
                            ])
                            // Set the variable MENDIX_SVN_REVISION to the extracted svn revision value
                            MENDIX_SVN_REVISION = MENDIX_SCM_VARS['SVN_REVISION']
                        }//dir
                    if ((env.BRANCH_NAME.equals('master')) && (buildProps['MENDIX_APP_BRANCH'] == 'trunk')){
                            fullVersion = "${buildProps['VERSION']}.${MENDIX_SVN_REVISION}-${currentBuild.number}"
                            NexusRepository = 'cpg-raw-releases'
                        }else {
                            NexusRepository = 'cpg-raw-prereleases'
                        if (buildProps['MENDIX_APP_BRANCH'].equals(env.BRANCH_NAME)) {
                            fullVersion = "${buildProps['VERSION']}.${MENDIX_SVN_REVISION}-${currentBuild.number}${buildProps['MENDIX_APP_BRANCH']}"
                        }else {
                            versionSuffix = env.BRANCH_NAME.replaceAll("/","_")
                            fullVersion = "${buildProps['VERSION']}.${MENDIX_SVN_REVISION}-${currentBuild.number}${buildProps['MENDIX_APP_BRANCH']}-${versionSuffix}"
                        }
                    }
                    currentBuild.displayName = fullVersion
                } // end of script
				sh " echo MENDIX_SVN_REVISION is ${MENDIX_SVN_REVISION} "
            } // end of steps
        } // end of stage - fetch source

/*
            stage ('build MDA deployment package') {
                steps {
                    script {
                        sh "echo Using Mendix CLI mxc to build MDA deployment package"
                        MENDIX_PACKAGE_NAME  = "${envProps['APPLICATION_NAME']}-${fullVersion}.mda"
                        if (buildProps['MENDIX_APP_BRANCH'] == 'trunk') {
                            sh "mxc -username ${MENDIX_USER_APIKEY_CREDS_USR} -apikey ${MENDIX_USER_APIKEY_CREDS_PSW} -project ${buildProps['MENDIX_APP_ID']} -destination ${MENDIX_PACKAGE_NAME} -tag ${buildProps['VERSION']} -revision ${MENDIX_SVN_REVISION}"
                        }else{
                            sh "mxc -username ${MENDIX_USER_APIKEY_CREDS_USR} -apikey ${MENDIX_USER_APIKEY_CREDS_PSW} -project ${buildProps['MENDIX_APP_ID']} -destination ${MENDIX_PACKAGE_NAME} -tag ${buildProps['VERSION']} -revision ${MENDIX_SVN_REVISION} -branch ${buildProps['MENDIX_APP_BRANCH']}"
                        }
                        sh "unzip ${MENDIX_PACKAGE_NAME} -d project"
                        sh "rm -rf ${MENDIX_PACKAGE_NAME}"
                    }
                } // end of steps
            } // end of stage - build MDA deployment package

*/
            stage('download buildpack'){
                steps {
                    sh "wget -qO-  https://devtools.cpggpc.ca/nexus/repository/github-mendixbuildpack-raw-proxy/app-insights-agent.tar.gz| tar xvz --strip-components 1"
                    sh 'rm -rf .git .gitignore'
                    sh 'ls -la'
                }
            }

            stage('Create the BuildConfig') {
                steps {
                    script {
                        def TAG = fullVersion
                        
                        cleanedBranchName = cleanBranchName(buildProps['MENDIX_APP_BRANCH'])
                        createBuildConfig(this,envProps['TEMPLATE'],TAG,cleanedBranchName)
                        
                    }
                }
            } // end of stage - Create Image Builder

            stage('Build Docker Image & Publish to Image Registry') {
                steps {
                    script {
                        openshift.withCluster(){
                            openshift.withProject(){
                                
                                buildOutput = openshift.selector("bc", "${envProps['APPLICATION_NAME']}-${cleanedBranchName}").startBuild("--from-dir=$WORKSPACE").logs('-f --timestamps')
                                
                                checkBuildForErrors(this, buildOutput)
                            }// oc project
                        }// oc clusterss
                    }
                }
            }// build image

            stage('Create Initialization Artifact') {
                when {
                    beforeAgent true
                    expression {
                        return (appInitFilesRequired)
                    }//end of expression
                }//end of when                
                steps {
                    script {
                         dir ('project'){
                            zip dir: "${buildProps['APPINITFOLDER']}", glob: '', zipFile: "${envProps['APPLICATION_NAME']}-init-${fullVersion}.zip"
                         }
                    }
                }
            }// Create Initialization Artifact

            stage('Publish Artifact(s) to Nexus') {
                when {
                    beforeAgent true
                    expression {
                        return (appInitFilesRequired)
                    }//end of expression
                }//end of when
                steps {
                    script {
                        dir ('project'){
                            nexusArtifactUploader(
                                nexusVersion: 'nexus3',
                                protocol: 'https',
                                nexusUrl: "${NEXUS_URL_BASE}",
                                groupId: "${envProps['ORGANIZATION']}.${envProps['PRODUCT']}",
                                version: "${fullVersion}",
                                repository: "${NexusRepository}",
                                credentialsId: 'jenkins-ss-pipeline-svc-ad',
                                artifacts: [
                                    [artifactId: "${envProps['APPLICATION_NAME']}",
                                    classifier: '',
                                    file: "${envProps['APPLICATION_NAME']}-init-${fullVersion}.zip",
                                    type: 'zip']
                                ]
                            )    
                        }// end of dir
                    } // end of script
                }// end of steps
            }// Publish Artifact(s) to Nexus

            stage('Create Unit Tests Env') {
                when {
                    beforeAgent true
                    expression {
                        return (runUnitTests)
                    }//end of expression
                }//end of when
                steps {
                    script {
                        def buildResultCreateUnitTestsEnvJob = build(job: "${envCreationJobName}", parameters: [string(name: 'AzureSubs', value: "${buildProps['MENDIX_AZURE_SUBSCRIPTION']}"), string(name: 'ENV', value: "${envProps['ORGANIZATION']}-${envProps['PRODUCT']}-${envProps['APPLICATION_NAME']}-ui-${unitTestEnv}"), string(name: 'DBSize', value: "GP_S_Gen5_2"), string(name: 'ACTION', value: "Create")])
                        if (buildResultCreateUnitTestsEnvJob.result == 'FAILURE') {
                            currentBuild.result = 'FAILURE'
                            error "Create Unit Tests Env Job Result: ${buildResultCreateUnitTestsEnvJob.result}"
                        }
                    }//end of scripts
                }//end of steps
            }//end of stage

            stage('Deploy to Unit Tests Env') {
                when {
                    beforeAgent true
                    expression {
                        return (runUnitTests)
                    }//end of expression
                }//end of when
                steps {
                    script {
                        def buildResultDeployjob = build(job: "${deployJobName}", parameters: [string(name: 'ENV2DEPLOY', value: "${unitTestEnv}"), string(name: 'IMAGETAG', value: "${fullVersion}")], propagate: false)
                        echo "Deploy Job Result: ${buildResultDeployjob.result}"
                        if (buildResultDeployjob.result == 'FAILURE') {
                            currentBuild.result = 'FAILURE'
                            error "Deploy Job Result: ${buildResultDeployjob.result}"
                        }
                    }//end of scripts
                }//end of steps
            }//end of stage

            stage('Run Unit Tests') {
                when {
                    beforeAgent true
                    expression {
                        return (runUnitTests)
                    }//end of expression
                }//end of when
                steps {
                    script {
                        APPURL = "https://${envProps['ORGANIZATION']}-${envProps['PRODUCT']}-${envProps['APPLICATION_NAME']}-ui-${unitTestEnv}.azcpggpc.ca"
                        MXUNTEST_START_URL  = "${APPURL}/${UNTEST_START_PATH}"
                        MXUNTEST_STATUS_URL = "${APPURL}/${UNTEST_STATUS_PATH}"
                        MXUNTEST_REPORT_URL = "${APPURL}/${UNTEST_REPORT_PATH}"
                    }

                    echo "Sending START request to this endpoint => ${MXUNTEST_START_URL}"
                    httpRequest authentication: 'jenkins-ss-pipeline-svc-mx-flowtest',
                       contentType: 'APPLICATION_JSON',
                       httpMode: 'POST',
                       ignoreSslErrors: true,
                       requestBody: "{ \"password\" : \"1\" }",
                       url: "${MXUNTEST_START_URL}",
                       validResponseCodes: '100:399'
                    echo "Sending STATUS request to this endpoint => ${MXUNTEST_STATUS_URL}"
                    timeout(time: "${STATUS_TIMEOUT}", unit: "${STATUS_TIMEOUT_UNITS}") {
                        script {
                            sleep(45) //calling status immediately gives 400, add wait
                            def sleepTimeSeconds = "${FLOWTEST_SLEEP_TIME}".toInteger()
                            def completed = false
                            def completionMarker = /"completed": true/
                            while(completed == false) {
                                def response = httpRequest authentication: 'jenkins-ss-pipeline-svc-mx-flowtest',
                                contentType: 'APPLICATION_JSON',
                                httpMode: 'POST',
                                ignoreSslErrors: true,
                                requestBody: "{ \"password\" : \"1\" }",
                                consoleLogResponseBody: true,
                                url: "${MXUNTEST_STATUS_URL}",
                                validResponseCodes: '100:399'
                                if (response.content =~ completionMarker) {
                                    completed = true
                                }
                                sleep(sleepTimeSeconds)
                            }
                        }
                    }
                    echo "Sending REPORT request to this endpoint => ${MXUNTEST_REPORT_URL}"
                    httpRequest authentication: 'jenkins-ss-pipeline-svc-mx-flowtest',
                    ignoreSslErrors: true,
                    outputFile: 'report-response.xml',
                    url: "${MXUNTEST_REPORT_URL}",
                    validResponseCodes: '100:399'
                    junit keepLongStdio: true,  healthScaleFactor: 10.0, testResults: 'report-response.xml'
                } // end of steps
             } // end of stage
            stage('Delete Unit Tests Env') {
                when {
                    beforeAgent true
                    expression {
                        return (runUnitTests && !(debugUnitTestsEnv))
                    }//end of expression
                }//end of when
                steps {
                    script {
                        def buildResultDeleteUnitTestsEnvJob = build(job: "${envCreationJobName}", parameters: [string(name: 'AzureSubs', value: "${buildProps['MENDIX_AZURE_SUBSCRIPTION']}"), string(name: 'ENV', value: "${envProps['ORGANIZATION']}-${envProps['PRODUCT']}-${envProps['APPLICATION_NAME']}-ui-${unitTestEnv}"), string(name: 'ACTION', value: "Delete")])
                        if (buildResultDeleteUnitTestsEnvJob.result == 'FAILURE') {
                            currentBuild.result = 'FAILURE'
                            error "Create Unit Tests Env Job Result: ${buildResultDeleteUnitTestsEnvJob.result}"
                        } else {
                            unitTestEnvDeleted = true
                        }
                    }//end of scripts
                }//end of steps
            }//end of stage
        } // end of stages
        post {
            always {
                script{
                    sendEmail(this,envProps[CPGBUILD_EMAIL_LIST_KEY])    
                    }
                }
            failure {
                script {
                    if (!unitTestEnvDeleted) {
                        if (runUnitTests && debugUnitTestsEnv) {
                            echo "Not deleting debugUnitTestsEnv is set - ${debugUnitTestsEnv}"
                        } else if (runUnitTests && !(debugUnitTestsEnv)) {
                            echo "Deleting debugUnitTestsEnv is not set - ${debugUnitTestsEnv}"
                            build(job: "${envCreationJobName}", parameters: [string(name: 'AzureSubs', value: "${buildProps['MENDIX_AZURE_SUBSCRIPTION']}"), string(name: 'ENV', value: "${envProps['ORGANIZATION']}-${envProps['PRODUCT']}-${envProps['APPLICATION_NAME']}-ui-${unitTestEnv}"), string(name: 'ACTION', value: "Delete")])
                        }
                    }
                }
            }
        } // end of post
    } // end of pipeline


} //end of call()
