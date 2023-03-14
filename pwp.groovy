import static com.innovapost.jenkins.BuildPipelineUtils.*;
import static com.innovapost.jenkins.CommonPipelineUtils.*;
import static com.innovapost.jenkins.BuildPipelineUtils.STAGES;
import static com.innovapost.jenkins.BuildPipelineUtils.downloadSuppressionRulesFile
import static com.innovapost.jenkins.BuildPipelineUtils.getSuppressionOptionOWASP
import static com.innovapost.jenkins.BuildPipelineUtils.getDependecyCheckArguments
import static com.innovapost.jenkins.BuildPipelineUtils.checkSonarQualityGate
import static com.innovapost.jenkins.BuildPipelineUtils.getAppVersion
import static com.innovapost.jenkins.CommonPipelineUtils.PIPELINE_TYPES
import static com.innovapost.jenkins.CommonPipelineUtils.ARTIFACT_TYPES
import static com.innovapost.jenkins.CommonPipelineUtils.getScmURL
import static com.innovapost.jenkins.CommonPipelineUtils.getNexusRepositoryName
import static com.innovapost.jenkins.TagUtils.generateImageTag
import static com.innovapost.jenkins.BuildPipelineUtils.getAppOrigin
import static com.innovapost.jenkins.TagUtils.addReleaseTag

def call(String overrideBranch = 'none', String agentName = 'jenkins-php-slave'){
	// have to define these here in order to set them in one script block
	// and read them in a different one
	Set pipelineStages = []
	String gitBranch;
	String cleanedBranchName  ;
	String artifactPackageName;
	String nexusRepository;
	String imageBuildPath;
	String appVersion;
	String waBuildPathsString;
	String sonarPhpCoverageReportPaths = ""
	String sonarPhpTestsReportPath = ""
	String appOrigin = APPORIGIN_OPENSHIFT
	final String WEB_ASSET_PATH_BUILD_KEY = 'CPGBUILD_WEB_ASSET_BUILD_PATHS'	

	// Check if stage is being retried
	script {
		retryStageCheck(this)
	}

	pipeline {
		agent {
			label "$agentName"
		}
       
	    /*
		Triggers directive to define the pipeline is retriggered
		cronTime takes the values of jenkins url and branch name defined in function
		*/
		triggers {
            cron("${cronTime(env.JENKINS_URL, env.BRANCH_NAME)}")
        }
		

		options {
			// To delete old builds and to only keep the latest 12 builds
			buildDiscarder(logRotator(numToKeepStr: NUM_BUILDS_TO_KEEP, daysToKeepStr: NUM_OF_DAYS_BUILDS_TO_KEEP))
            timestamps() //timestamper plugin
			lock(label: 'pod', quantity: 2)
		}

		environment {
			 BUILD_NUMBER             = "$BUILD_NUMBER"
			 JOB_NAME                 = "$JOB_NAME"
			 JENKINS_URL              = "$JENKINS_URL"
			 NEXUS_URL_BASE_W_PROTO   = "https://$NEXUS_URL_BASE"
			 SONAR_SCANNER_OPTS       = '-Xmx1500M'
			 SONAR_CRED                = credentials('jenkins-ss-pipeline-svc-sonar-tokenkey')   
		}

		stages {
			stage('Determine Stages'){
				steps {
					script {
						// Read Project properties that will be used later
						(envProps, buildProps) = readConfigFiles(this)

						gitBranch = getGitBranch(overrideBranch, env.GIT_BRANCH)
						echo "git branch: $gitBranch"

						boolean autoCommit = isAutoCommit()
						echo "autoCommit: ${autoCommit}"

						pipelineStages = computePipelineStages(gitBranch, isPR(env.CHANGE_ID), getChangeTarget(env.CHANGE_TARGET), isManualBuild(this), autoCommit, isLibrary(envProps), isTimerTriggeredBuild(this))
						echo "Execute following pipeline stages: $pipelineStages"

						// String currentDate = date.format( 'yyyyMMdd' )
						// prep variables required for artifact upload.
						appVersion = getAppVersion(buildProps)
						echo "app version number: ${appVersion}"
						nexusRepository = getNexusRepositoryName(JENKINS_URL, env.GIT_BRANCH, ARTIFACT_TYPES.RAW)
						echo "nexusRepository : ${nexusRepository}"
						
						waBuildPathsString = buildProps[WEB_ASSET_PATH_BUILD_KEY]
						echo waBuildPathsString

						appOrigin=getAppOrigin(buildProps)

					//	sleep time: 60, unit: 'MINUTES'
					}
				}
			}
			stage('Build - Checkout WP-CLI') {
				steps {
					script {
						sh 'curl -0 https://git.cpggpc.ca/projects/WP/repos/wp-cli/raw/2.4.0/wp-cli.phar?at=refs%2Fheads%2Fmaster -o wp-cli.phar'
						sh 'chmod +x wp-cli.phar'
						sh 'mv wp-cli.phar wp'
						sh './wp --info'
					}
				}
			}
			stage('Build - Web Asset') {
				when {
					beforeAgent true
						expression {
							return (waBuildPathsString != null)
							}//expression
						}//when
				steps {
					script {
						String[] buildPaths = waBuildPathsString.split(';')
							buildPaths.each{
								dir("$it"){
									sh 'npm run jenkins'
								}
							}
						}
					}
				post {
					always {
						echo '''
							TODO: update path below to reflect where you unit test results are saved. For example
							junit '**/surefire-reports/*.xml'
							'''
							//
						}
					}
				}

			stage('Build - Zip Artifact') {
				steps {
					script {
						if("$gitBranch" =~ "release" || "$gitBranch" =~ "hotfix")
						{
						   artifactPackageName = "${envProps['APPLICATION_NAME']}-${appVersion}.tar.gz"
						} else {
						   artifactPackageName = "${envProps['APPLICATION_NAME']}-${appVersion}-SNAPSHOT.tar.gz"
						}
						echo "artifactPackageName : ${artifactPackageName}"
						createTargetFile(env.WORKSPACE, artifactPackageName)
						imageBuildPath = getTargetFilePath(env.WORKSPACE, artifactPackageName)
					}
				}
			}

			stage('Build & Dependency Scan') {
				when {
					beforeAgent true
					expression {
						return (pipelineStages.contains(STAGES.BUILD_AND_UNIT_TEST))
					}
				}
				parallel {
					stage('Unit Test') {
						steps {
							script {
                                String coverageReportPaths = ""
                                String testsReportPath = ""
                                String reportsBasePath = "${env.WORKSPACE}/target/reports"
                                def unitTestDirectoryList = buildProps['CPGBUILD_PHP_UNITTEST_LIST'] ? buildProps['CPGBUILD_PHP_UNITTEST_LIST'].split(',') : []
								sh 'composer -V'
								sh 'composer update --dev'
								echo "Check app local phpunit version"
								sh 'vendor/phpunit/phpunit/phpunit --version'
								sh 'node --version'
								sh 'sonar-scanner --version'
								if ( unitTestDirectoryList.length == 0 ) {
									echo "WARNING: No test folders were specified in build.values (CPGBUILD_PHP_UNITTEST_LIST) - not running any unit tests"
								} else {
									sh """
                                      if [ ! -d ${reportsBasePath} ]; then
		                                mkdir -p ${reportsBasePath}/{coverage-reports,unit-test-reports}
	                                  fi
	                                """
									unitTestDirectoryList.each {
										if ( fileExists ( "${it}/phpunit.xml" ) ) {
											echo "Running phpunit.xml tests in folder ${it}"
											String tempPath = "${reportsBasePath}/coverage-reports/${it}"
											String testReportName = it.replaceAll("/","-")
											sh "mkdir -p ${tempPath}"
											coverageReportPaths = "${tempPath}/coverage-clover-report.xml" + ",${coverageReportPaths}"
											testReportFile = "${reportsBasePath}/unit-test-reports/${testReportName}-phpunit-report.xml"
											// sh "php -dpcov.enabled=1 -dpcov.directory=${it} --coverage-xml=${env.WORKSPACE}/php-coverage-reports vendor/phpunit/phpunit/phpunit -c ${it}/phpunit.xml"
                                            sh "vendor/phpunit/phpunit/phpunit --coverage-clover=${tempPath}/coverage-clover-report.xml --log-junit=${testReportFile} -c ${it}/phpunit.xml"
										} else {
											echo "WARNING: Test folder ${it} from CPGBUILD_PHP_UNITTEST_LIST does not contain a file named ${it}/phpunit.xml"
										}
									}
									if ( coverageReportPaths != null && coverageReportPaths.length() > 0 && coverageReportPaths.charAt(coverageReportPaths.length() - 1) == ',') {
									    sh "junit-merge  -o ${reportsBasePath}/phpunit-report-results.xml -d ${reportsBasePath}/unit-test-reports/"
									    coverageReportPaths = coverageReportPaths.substring(0, coverageReportPaths.length() - 1)
									    sonarPhpCoverageReportPaths = "-Dsonar.php.coverage.reportPaths=${coverageReportPaths} "
									    sonarPhpTestsReportPath = "-Dsonar.php.tests.reportPath=${reportsBasePath}/phpunit-report-results.xml "
									    // sonarPhpTestsReportPath = "-Dsonar.php.tests.reportPath=${reportsBasePath}/unit-test-reports/public-wp-content-plugins-cpg-ldap-login-phpunit-report.xml "
									}
								}
                            	// sleep time: 60, unit: 'MINUTES'
							}
						}
						post {
							always {
									junit '**/target/reports/phpunit-report-results.xml'
							}
						}
					}

					stage('OWASP Scanning') {
						when {
							beforeAgent true
							expression {
								return (pipelineStages.contains(STAGES.OWASP_SCAN))
							}//expression
						}//when
						steps {
							script {
								downloadSuppressionRulesFile(this, PIPELINE_TYPES.PWP)
								String suppressionOption = getSuppressionOptionOWASP(this)
								odcArguments = getDependecyCheckArguments(this, envProps['APPLICATION_NAME'], buildProps['CPGBUILD_DEPENDENCY_CHECK_DIRS'], 'ALL', suppressionOption )
								echo "odcArguments is ${odcArguments}"
								// publish scan results to Jenkins
								//sleep time: 30, unit: 'MINUTES'
								dependencyCheck additionalArguments: "${odcArguments}", odcInstallation: "dependency-check"
								dependencyCheckPublisher pattern: '.owasp/dependency-check-report.xml'
								handlePotentialDcBug()						
							}
						}
					} //End of 'OWASP Scanning'
				} //End of parallel
			} //End of Stage 'Code Scanning'

			stage('SonarQube Scanning') {
				when {
					beforeAgent true
					expression {
						return (pipelineStages.contains(STAGES.SONAR_SCAN))
					}//expression
				}//when
				steps {
					withSonarQubeEnv("$SONARQUBE_INSTANCE") {
						script {

							String sonarInclusions = buildProps['CPGBUILD_SONAR_INCLUSIONS_LIST'] ? "-Dsonar.inclusions=${buildProps['CPGBUILD_SONAR_INCLUSIONS_LIST']} " : ""
							String sonarExclusions = buildProps['CPGBUILD_SONAR_EXCLUSIONS_LIST'] ? "-Dsonar.exclusions=${buildProps['CPGBUILD_SONAR_EXCLUSIONS_LIST']} " : ""
							
							def phpScanOptions = "-Dsonar.login=${SONAR_CRED_PSW} " +
									"-Dsonar.host.url=${SONARQUBE_BASE_URL} " +
									"-Dsonar.projectKey=${envProps['APPLICATION_NAME']} " +
									"-Dsonar.projectName=${envProps['APPLICATION_NAME']} " +
									"-Dsonar.projectVersion=${buildProps['CPGBUILD_VERSION']} " +
									"-Dsonar.sources=${env.WORKSPACE} " +
									"-Dsonar.tests=${env.WORKSPACE} " +
									"-Dsonar.test.inclusions=**/tests/*-Test.php "+
									"-Dphpmodule.sonar.language=php " +
									"-Dsonar.sourceEncoding=UTF-8 " +
									"-Dphpmodule.sonar.projectBaseDir=${env.WORKSPACE} " +
									"${sonarInclusions} " +
									"${sonarExclusions} " +
									"${sonarPhpCoverageReportPaths} " +
									"${sonarPhpTestsReportPath} " +
									"-Dsonar.dependencyCheck.htmlReportPath=${env.WORKSPACE}/.owasp/dependency-check-report.html " +
									"-Dsonar.dependencyCheck.jsonReportPath=${env.WORKSPACE}/.owasp/dependency-check-report.json "				
							sh "sonar-scanner ${phpScanOptions}"
						}
					}
				} // end of steps
				post {
					//  For deprecated pipelines, the job is marked as unstable. As a result the usual condition "SUCCESS" that we use to check SQ quality gate will not work. 
					//  Hence "always" condition is added when checking quality gate.
					always {
						timeout(time: 20, unit: 'MINUTES') {
							// Parameter indicates whether to set pipeline to UNSTABLE if Quality Gate fails
							// true = set pipeline to UNSTABLE, false = don't
							// Requires SonarQube Scanner for Jenkins 2.7+
							// Requires webhook setup on SonarQube
							script {
								checkSonarQualityGate(this, appOrigin)
								echo "SonarQube Project Dashboard: ${SONARQUBE_BASE_URL}/dashboard?id=${envProps['APPLICATION_NAME']}"

								setSonarQubeProjectTags(this, "${SONAR_CRED_PSW}")

							}
						}
					}
				}
			} //End of Stage 'SonarQube Scanning'

			stage('Save Snapshot Artifact') {
				when {
					beforeAgent true
					expression {
						return (pipelineStages.contains(STAGES.BUILD_SNAPSHOT_ARTIFACT))
					}
				}
				steps {
					script {
						nexusArtifactUploader (
							nexusVersion: 'nexus3',
							protocol: 'https',
							nexusUrl: '${NEXUS_URL_BASE}',
							groupId: "${envProps['ORGANIZATION']}.${envProps['PRODUCT']}",
							version: "${appVersion}-SNAPSHOT",
							repository: "${nexusRepository}",
							credentialsId: 'jenkins-ss-pipeline-svc-ad',
							artifacts: [
								[artifactId: "${envProps['APPLICATION_NAME']}",
								classifier: '',
								file: "target/${artifactPackageName}",
								type: 'tar.gz']
							]
 						)
					}
				}
			}

			stage('Build and Save Release Artifact') {
				when {
					beforeAgent true
					expression {
						return (pipelineStages.contains(STAGES.BUILD_RELEASE_ARTIFACT))
					}
				}
				steps {
					echo "This is a release"
					script {
						echo "artifactPackageName : ${artifactPackageName}"
						String appArtifactUrl = appArtifactUrl(nexusRepository, appVersion, artifactPackageName)
						def artifactExist =  sh ( script: "curl -s --head -w %{http_code} ${appArtifactUrl} -o /dev/null",  returnStdout: true).trim()
						if ( artifactExist != '200' ) {
							nexusArtifactUploader (
								nexusVersion: 'nexus3',
								protocol: 'https',
								nexusUrl: '${NEXUS_URL_BASE}',
								groupId: "${envProps['ORGANIZATION']}.${envProps['PRODUCT']}",
								version: "${appVersion}",
								repository: "${nexusRepository}",
								credentialsId: 'jenkins-ss-pipeline-svc-ad',
								artifacts: [
									[artifactId: "${envProps['APPLICATION_NAME']}",
									classifier: '',
									file: "target/${artifactPackageName}",
									type: 'tar.gz']
								]
							)
						} else {
							error "FAILED: ${artifactPackageName} package already exist"
						}
					}
				}
			}

			stage('Prepare For Image Build') {
				when {
					expression {
						return pipelineStages.contains(STAGES.BUILD_IMAGE)
					}
				}
				steps {
					script {
						String tag = generateImageTag(appVersion, BUILD_NUMBER, gitBranch, GIT_COMMIT)
						currentBuild.displayName = tag
						echo "Image Tag name: ${tag}"
						String SOURCE_URI = getScmURL(env);
						String appArtifactUrl = appArtifactUrl(nexusRepository, appVersion, artifactPackageName)
						
						cleanedBranchName = cleanBranchName(GIT_BRANCH)
						createBuildConfig(this,envProps['TEMPLATE'],tag,cleanedBranchName,SOURCE_URI,GIT_BRANCH,appArtifactUrl)
						
					}
				}
			}

			stage('Build Image') {
				when {
					expression {
						return (pipelineStages.contains(STAGES.BUILD_IMAGE))
					}
				}
				environment {
            		BRANCH_NAME         = "$BRANCH_NAME"
					// automatically sets SCM_CREDS_USR and SCM_CREDS_PSW
            		SCM_CREDS           = credentials('jenkins-ss-pipeline-svc-ad')
					SCM_URL             = getScmUrlProxy()
					REPO_CONTEXT_PATH	= com.innovapost.jenkins.BuildPipelineUtils.getRepoContextPath("${SCM_URL}")
				}				
				steps {
					script {

						String baseimage = "${buildProps['PHP_BASE_IMAGE']}"
                        warnIfDeprecatedBaseImage(this , baseimage)

						openshift.withCluster() {
                            buildOutput = openshift.selector('bc', "${envProps['APPLICATION_NAME']}-${cleanedBranchName}").startBuild("--from-dir=${imageBuildPath}").logs('-f --timestamps')
						}

						checkBuildForErrors(this, buildOutput)

						// Set release tag	
						String branchName =  "${BRANCH_NAME}";

						// Only tag release branch
						if ( branchName.toLowerCase().contains("release")) {						
							String appName = envProps['APPLICATION_NAME'];		
							addReleaseTag(this, appName, appVersion);
						}
						else {
							echo "Not creating tag as this is not a release branch."
						}
					}				
				}
			}
		}
		post {
            always {
                script {
                       sendEmail(this,envProps[CPGBUILD_EMAIL_LIST_KEY])
                    }
                }
        } // end of post notifications
	}
}

/**
 * Find the path to the built artifact
 * @return path to the artifact that was created by the build process
 */
private String getTargetFilePath(String artifactPath, String artifactPackageName) {
	// sh 'echo TODO: implement getTargetFilePath()'
	sh """
	   if [ -d ${artifactPath}/target/buildDir ]; then
		   rm -rf ${artifactPath}/target/buildDir
	   fi
	   mkdir -p ${artifactPath}/target/buildDir
	   tar -xvf ${artifactPath}/target/${artifactPackageName} --directory ${artifactPath}/target/buildDir
	"""
	String targetBuildPath = "${artifactPath}/target/buildDir"
	return targetBuildPath

}

/**
 * create artifact package in *.tar.gz format
 *
 * @param artifactPath
 * @param artifactPackageName 
 */
private createTargetFile(String artifactPath, String artifactPackageName) {
	sh	"echo INFO: createTargetFile"
	sh	"tar -czvf /tmp/${artifactPackageName} -X ${artifactPath}/.s2iignore -C ${artifactPath} ./"
	sh """
	   if [ ! -d ${artifactPath}/target ]; then
		   mkdir -p ${artifactPath}/target
	   fi
	"""
	sh	"mv /tmp/${artifactPackageName} ${artifactPath}/target/"
	sh	"ls -al ${artifactPath}/target/"
}

private String appArtifactUrl (String nexusRepository, String appVersion, String artifactPackageName ){
	final String artifactURL = "${NEXUS_URL_BASE_W_PROTO}/repository/${nexusRepository}/${envProps['ORGANIZATION']}/${envProps['PRODUCT']}/${envProps['APPLICATION_NAME']}/${appVersion}/${artifactPackageName}"
	return artifactURL
}

/**
 * 
 * if your build system does auto commits to git, please add logic here to detect if the last commit was an auto commit.
 * this is required so that we do not trigger pipelines runs/jobs from auto commits that can then create more auto commits and lead to an infinite loop. 
 * @return false
 */
private boolean isAutoCommit() {
	return false;
}

def getScmUrlProxy() {
	//there is some weird stuff with how the env block works and how
	//the jvm limits a method size
	//if we try to make the real call from the env block we get an
	//error - General error during class generation: Method code too large!
	//We will use this local method to proxy the shared one, this is not
	//ideal but better than duplicating the logic
	return getScmURL(env);
}
