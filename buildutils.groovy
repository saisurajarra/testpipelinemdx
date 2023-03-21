package com.innovapost.jenkins
import static com.innovapost.jenkins.CommonPipelineUtils.*
import static com.innovapost.jenkins.CommonPipelineUtils.PIPELINE_TYPES
import static com.innovapost.jenkins.CommonPipelineUtils.getScmURL

class BuildPipelineUtils {
    
    BuildPipelineUtils() {
    }

    // createBuildConfig (Script Pipeline context, String Build template to use, String Image tag to be used, Source URI  for pwp builds, String Source branch for pwp builds )
	static void createBuildConfig(Script script, String template, String tag, String branch = 'none', String source = 'none', String pwpSourceBranch = 'none', String appArtifactUrl = 'none') {
        def created = []
        def models = []
       
        script.openshift.withCluster() {
            script.echo 'Applying buildConfig'
            script.echo "Template: $template"
            script.sh "cat project.values | sed -e '/EMAIL_LIST=/d' | sed -e '/TEMPLATE=/d' | sed -e '/PROJECT_DESCRIPTION=/d' > input.values"
            script.sh "cat build.values | sed -e '/LIB_/d' | sed -e '/VERSION=/d' | sed -e '/MENDIX_/d' | sed -e '/APPINITFOLDER=/d' | sed -e '/CPGBUILD_/d'  >> input.values"
			if(branch == 'none'){
				script.error("Please update the build config, we have removed support for the deprecated build configs (As Per Item 2 in Release 2021-09-07: 2.25.0)")
			}
			else {
				if (source != "none"){
					models = script.openshift.process( "openshift//${template}", "--param-file", "input.values", "-p", "TAG=${tag}", "-p", "BRANCH=${branch}", "-p", "SOURCE_URI=${source}", "-p", "SOURCE_BRANCH=${pwpSourceBranch}", "-p", "APP_ARTIFACT_URL=${appArtifactUrl}")
				}
				else {
					models = script.openshift.process( "openshift//${template}", "--param-file", "input.values", "-p", "TAG=${tag}", "-p", "APP_ARTIFACT_URL=${appArtifactUrl}", "-p", "BRANCH=${branch}")
				}
			}
			models.eachWithIndex { item, i -> created[i] = script.openshift.apply( item ) }
        }//cluster
    }

	/**	
	 * Performs clean of Branch name replacing slashs and underscores with dashes with a length limit of 50
	 *
	 * @param	branchName	Git branch name
	 * @return	Cleaned branch name
	 */
	public static String cleanBranchName(String branchName) {
		String trimBranchName = branchName.replaceAll(/\//,'-').replaceAll(/_/,'-').toLowerCase().take(50).trim().replaceAll("-+\$", "")
		return trimBranchName
	}
