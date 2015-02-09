/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package de.charite.compbio.exomiser.core.writers;

import de.charite.compbio.exomiser.core.writers.ResultsWriterUtils;
import de.charite.compbio.exomiser.core.writers.OutputFormat;
import de.charite.compbio.exomiser.core.writers.VariantTypeCount;
import de.charite.compbio.exomiser.core.filters.FilterReport;
import de.charite.compbio.exomiser.core.ExomiserSettings;
import static de.charite.compbio.exomiser.core.ExomiserSettings.DEFAULT_OUTPUT_DIR;
import de.charite.compbio.exomiser.core.ExomiserSettings.SettingsBuilder;
import de.charite.compbio.exomiser.core.model.SampleData;
import de.charite.compbio.exomiser.core.model.VariantEvaluation;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author Jules Jacobsen <jules.jacobsen@sanger.ac.uk>
 */
public class ResultsWriterUtilsTest {

    SettingsBuilder settingsbuilder;
    
    public ResultsWriterUtilsTest() {
    }

    @Before
    public void before() {
        settingsbuilder = new ExomiserSettings.SettingsBuilder();
        settingsbuilder.vcfFilePath(Paths.get("wibble"));
    }

    @Test
    public void testThatSpecifiedTsvFileExtensionIsPresent() {
        OutputFormat testedFormat = OutputFormat.TSV_GENE;
        ExomiserSettings settings = settingsbuilder.build();
        String expResult = String.format("%s/wibble-exomiser-results.%s", DEFAULT_OUTPUT_DIR, testedFormat.getFileExtension());
        String result = ResultsWriterUtils.determineFileExtension(settings.getOutFileName(), testedFormat);
        assertThat(result, equalTo(expResult));
    }

    @Test
    public void testThatSpecifiedVcfFileExtensionIsPresent() {
        OutputFormat testedFormat = OutputFormat.VCF;
        ExomiserSettings settings = settingsbuilder.build();
        String expResult = String.format("%s/wibble-exomiser-results.%s", DEFAULT_OUTPUT_DIR, testedFormat.getFileExtension());
        String result = ResultsWriterUtils.determineFileExtension(settings.getOutFileName(), testedFormat);
        assertThat(result, equalTo(expResult));
    }

    @Test
    public void testThatSpecifiedOutputFormatOverwritesGivenOutFileExtension() {
        OutputFormat testedFormat = OutputFormat.VCF;
        settingsbuilder.outFileName("/user/jules/exomes/analysis/slartibartfast.xml");
        ExomiserSettings settings = settingsbuilder.build();        
        String expResult = String.format("/user/jules/exomes/analysis/slartibartfast.%s", testedFormat.getFileExtension());
        String result = ResultsWriterUtils.determineFileExtension(settings.getOutFileName(), testedFormat);
        assertThat(result, equalTo(expResult));
    }

    @Test
    public void testDefaultOutputFormatIsNotDestroyedByIncorrectFileExtensionDetection() {
        OutputFormat testedFormat = OutputFormat.HTML;
        settingsbuilder.buildVersion("2.1.0");
        ExomiserSettings settings = settingsbuilder.build();
        String expResult = DEFAULT_OUTPUT_DIR + "/wibble-exomiser-2.1.0-results.html";
        String result = ResultsWriterUtils.determineFileExtension(settings.getOutFileName(), testedFormat);
        assertThat(result, equalTo(expResult));
    }
    
    @Test
    public void canMakeEmptyVariantTypeCounterFromEmptyVariantEvaluations() {
        List<VariantEvaluation> variantEvaluations = new ArrayList<>();
        List<VariantTypeCount> variantTypeCounters = ResultsWriterUtils.makeVariantTypeCounters(variantEvaluations);
        assertThat(variantTypeCounters.isEmpty(), is(false));
        
        VariantTypeCount firstVariantTypeCount = variantTypeCounters.get(0);
        assertThat(firstVariantTypeCount.getVariantType(), notNullValue());
        assertThat(firstVariantTypeCount.getSampleVariantTypeCounts().isEmpty(), is(true));
    }
    
    @Test
    public void canMakeFilterReportsFromSettingsAndVariantEvaluations(){
        ExomiserSettings settings = new ExomiserSettings.SettingsBuilder().build();
        SampleData sampleData = new SampleData();
        sampleData.setVariantEvaluations(new ArrayList<VariantEvaluation>());
        List<FilterReport> results = ResultsWriterUtils.makeFilterReports(settings, sampleData);
        
        for (FilterReport result : results) {
            System.out.println(result);
        }
        
        assertThat(results.isEmpty(), is(false));
    }
}
