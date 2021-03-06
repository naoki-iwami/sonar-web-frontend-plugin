package fr.sii.sonar.web.frontend.css.quality;

import org.sonar.api.rules.RuleFinder;

import fr.sii.sonar.report.core.quality.profile.JsonProfileParser;
import fr.sii.sonar.report.core.quality.profile.ProfileFileDefinition;

/**
 * Just a specific implementation to help dependency injection
 * 
 * @author Aurélien Baudet
 *
 */
public class CssLintProfile extends ProfileFileDefinition {

	public CssLintProfile(RuleFinder ruleFinder, CssLintQualityConstants constants) {
		super(constants.getProfileJsonPath(), new JsonProfileParser(), ruleFinder);
	}

}
