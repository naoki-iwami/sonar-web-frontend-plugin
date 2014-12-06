package fr.sii.sonar.report.core.coverage.saver;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.CoverageMeasuresBuilder;
import org.sonar.api.measures.Measure;
import org.sonar.api.measures.PropertiesBuilder;
import org.sonar.api.resources.File;
import org.sonar.api.resources.Project;
import org.sonar.api.scan.filesystem.FileQuery;

import fr.sii.sonar.report.core.common.PluginContext;
import fr.sii.sonar.report.core.common.exception.SaveException;
import fr.sii.sonar.report.core.common.save.Saver;
import fr.sii.sonar.report.core.coverage.domain.CoverageReport;
import fr.sii.sonar.report.core.coverage.domain.FileCoverage;
import fr.sii.sonar.report.core.coverage.domain.LineCoverage;

/**
 * Generic coverage saver that generates Sonar measures for code coverage from
 * any coverage report. The report may contain only lines for the covered files.
 * If a file is not in the report, it doesn't mean that the file is not in the
 * project. That's why this saver must also loop through all source files and
 * generate no coverage for each source file that is not in the report.
 * 
 * @author Aurélien Baudet
 *
 */
public class CoverageSaver implements Saver<CoverageReport> {
	private static final Logger LOG = LoggerFactory.getLogger(CoverageSaver.class);

	/**
	 * The sonar plugin context
	 */
	private PluginContext pluginContext;

	public CoverageSaver(PluginContext pluginContext) {
		super();
		this.pluginContext = pluginContext;
	}

	public void save(CoverageReport report, Project project, SensorContext context) {
		// save coverage information provided by report file
		for (FileCoverage file : report.getFiles()) {
			saveCoverage(project, context, report, file);
		}
		// the list of files in the report file may not cover all sources files =>
		// must create empty coverage entry for each file that is not present in
		// report file
		for (java.io.File sourceFile : pluginContext.getFilesystem().files(FileQuery.onSource().onLanguage(pluginContext.getConstants().getLanguageKey()))) {
			try {
				if (!hasCoverage(report, sourceFile)) {
					saveZeroValueForResource(File.fromIOFile(sourceFile, project), context);
				}
			} catch (IOException e) {
				LOG.warn("failed to determine if coverage is provided by coverage report for " + sourceFile.getAbsolutePath());
				saveZeroValueForResource(File.fromIOFile(sourceFile, project), context);
			}
		}
	}

	/**
	 * Check if the source file appears in the coverage file
	 * 
	 * @param report
	 *            the coverage report
	 * @param sourceFile
	 *            the project source file
	 * @return true if the source file is present in the coverage report, false
	 *         otherwise
	 * @throws IOException
	 *             when a file can't be read
	 */
	protected boolean hasCoverage(CoverageReport report, java.io.File sourceFile) throws IOException {
		for (FileCoverage file : report.getFiles()) {
			java.io.File coverageFile = getAnalyzedFilePath(report, file);
			if (sourceFile.getCanonicalPath().equals(coverageFile.getCanonicalPath())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Save coverage information for the provided source file
	 * 
	 * @param project
	 *            the sonar project
	 * @param context
	 *            the sonar context
	 * @param report
	 *            the coverage report
	 * @param file
	 *            the file coverage information to save
	 */
	protected void saveCoverage(Project project, SensorContext context, CoverageReport report, FileCoverage file) {
		// try to load the sonar file from real file system
		File sonarFile = getSourceFile(project, report, file);
		if (sonarFile == null) {
			LOG.error("The file " + getAnalyzedFilePath(report, file) + " doesn't exist. No coverage will be generated for this file");
			if (pluginContext.getSettings().getBoolean(pluginContext.getConstants().getMissingFileFailKey())) {
				throw new SaveException("The file " + getAnalyzedFilePath(report, file) + " doesn't exist");
			}
		} else {
			CoverageMeasuresBuilder result = CoverageMeasuresBuilder.create();
			for (LineCoverage line : file.getLines()) {
				// generate line coverage measure
				result.setHits(line.getLine(), line.getExecutionCount());
				// generate branch coverage measure
				if(line.getBranchCoverage()!=null) {
					result.setConditions(line.getLine(), line.getBranchCoverage().getConditions(), line.getBranchCoverage().getCovered());
				}
			}
			// save generated measures
			for (Measure measure : result.createMeasures()) {
				context.saveMeasure(sonarFile, measure);
			}
		}
	}

	/**
	 * Save a no coverage entry for a source file that is not present in the
	 * coverage report. This information can only be added if there are
	 * {@link CoreMetrics}.LINE and {@link CoreMetrics}.NCLOC information
	 * already stored about the source file in sonar. This is done with another
	 * {@link Sensor} that provides quality information
	 * 
	 * @param resource
	 *            the sonar source file
	 * @param context
	 *            the sonar context
	 */
	protected void saveZeroValueForResource(File resource, SensorContext context) {
		PropertiesBuilder<Integer, Integer> lineHitsData = new PropertiesBuilder<Integer, Integer>(CoreMetrics.COVERAGE_LINE_HITS_DATA);

		Measure measure = context.getMeasure(resource, CoreMetrics.LINES);
		if (measure != null) {
			for (int x = 1; x < measure.getIntValue(); x++) {
				lineHitsData.add(x, 0);
			}
		}

		// use non comment lines of code for coverage calculation
		Measure ncloc = context.getMeasure(resource, CoreMetrics.NCLOC);
		if (ncloc != null) {
			context.saveMeasure(resource, lineHitsData.build());
			context.saveMeasure(resource, CoreMetrics.LINES_TO_COVER, ncloc.getValue());
			context.saveMeasure(resource, CoreMetrics.UNCOVERED_LINES, ncloc.getValue());
		}
	}

	/**
	 * Get the sonar file from either absolute path or relative path to source
	 * directories
	 * 
	 * @param report
	 *            the quality report
	 * @param project
	 *            the project under plugin execution
	 * @param file
	 *            the report file information that contains path
	 * @return the sonar file
	 */
	private File getSourceFile(Project project, CoverageReport report, FileCoverage file) {
		File sourceFile = File.fromIOFile(getAnalyzedFilePath(report, file), project);
		if(sourceFile==null) {
			sourceFile = File.fromIOFile(new java.io.File(file.getPath()), pluginContext.getFilesystem().sourceDirs());
		}
		return sourceFile;
	}

	/**
	 * Get the real path to the file
	 * 
	 * @param report
	 *            the coverage report
	 * @param file
	 *            the file coverage information that contains raw path (may be
	 *            relative to the report)
	 * @return the real path to the file
	 */
	private java.io.File getAnalyzedFilePath(CoverageReport report, FileCoverage file) {
		java.io.File f = new java.io.File(pluginContext.getFilesystem().baseDir(), file.getPath());
		return f.exists() ? f : new java.io.File(file.getPath());
	}

}
