/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package de.charite.compbio.exomiser.parsers;

import de.charite.compbio.exomiser.resources.ResourceOperationStatus;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parser for the pheno2gene.txt file which produces the omim2gene.pg dump file,
 * assuming you want to call them that.
 *
 * The parser expects a file of the format:
 * <pre>
 * Warburg micro syndrome|600118|10p12.1|Warburg micro syndrome 3|3|614222|RAB18, WARBM3|602207|22931|RAB18
 * </pre> 
 * We are interested in the following fields.
 * <ol>
 * <li> Warburg micro syndrome: name of the phenoseries
 * <li> 600118: MIM Id of the main entry of the phenoseries, we will use it as a
 * PRIMARY KEY
 * <li> 10p12.1: Cytoband of the specific disease entry
 * <li> Warburg micro syndrome 3: Name of the specific disease entry
 * <li> 3: OMIM class of the specific disease entry (3 means gene has been
 * mapped)
 * <li> 614222: MIM Id (phenotype) of the specific disease entry
 * <li> RAB18, WARBM3: gene symbol and synonyms of the specific disease entry
 * <li> 602207: MIM Id (gene) of the specific disease entry
 * <li> 22931: Entrez Gene Id of the specific disease entry's gene
 * <li> Gene symbol of the Entrez Gene entry (should match one of the items in
 * field 7).
 * </ol>
 * 
 * and produces a pipe delimited file of this format:
 * <pre>
 * 614222|Warburg micro syndrome 3|10p12.1|602207|22931|RAB18|600118
 * </pre>
 *
 * @author Jules Jacobsen <jules.jacobsen@sanger.ac.uk>
 */
public class Omim2GeneParser implements Parser {

    private static final Logger logger = LoggerFactory.getLogger(Omim2GeneParser.class);
    
    
    @Override
    public ResourceOperationStatus parse(String inPath, String outPath) {
        logger.info("Parsing {}", inPath);
        //you might notice that the code here is pretty similar to that in the 
        //PhenoSeriesParser because it is parsing the same file, but handling the data slightly differently.
        //Sorry. This is clunky and a cardinal sin in direct violation of DRY. 
        //But done to fit the parse() paradigm. Peter did it better before-hand 
        //(i.e. one class only), but it produced two different tables. Given this is static data we're parsing
        //and it's likely to be depricated at some point this is hopefully not too evil.
        try (BufferedReader reader = new BufferedReader(new FileReader(inPath));
                BufferedWriter writer = new BufferedWriter(new FileWriter(outPath))) {

            Set<String> uniqueSeriesIds = new HashSet<>();
            
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug(line);
                String[] fields = line.split("\\|");
        //        "INSERT INTO omim2gene(mimDiseaseID, mimDiseaseName,cytoBand,mimGeneID,entrezGeneID,geneSymbol,seriesID) "+
                final int expectedFields = 10;
                if (fields.length != expectedFields) {
                    logger.error("Expected {} fields but got {} for line {}", expectedFields, fields.length, line);
                    continue;
                }
                
		String seriesId = fields[1];
		String cytoBand = fields[2];
		String mimDiseaseName = fields[3];
		String mimDiseaseId = fields[5];
		String mimGeneId = fields[7];
		String entrezGeneId = fields[8];
		String geneSymbol = fields[9];
                
                String uniqueSeriesId = String.format("%s-%s", seriesId, mimDiseaseId);
                
                if (entrezGeneId.equals("?")) {
                    logger.debug("No Entrez gene mapped for phenoseries: {} diseaseId: {} MIM gene: {} location: {} name:{}", seriesId, mimDiseaseId, mimGeneId, cytoBand, mimDiseaseName); // No gene for this entry
                } else if (uniqueSeriesIds.contains(uniqueSeriesId)) {
                    //is this a bug with the original data?
                    logger.debug("diseaseId {} has already been mapped to phenoseries: {}. Skipping diseaseId: {} MIM gene: {} {} location: {} name:{}", mimDiseaseId, seriesId, mimDiseaseId, mimGeneId, geneSymbol, cytoBand, mimDiseaseName);
                } else {
                    uniqueSeriesIds.add(uniqueSeriesId);
                    logger.debug(String.format("%s|%s|%s|%s|%s|%s|%s%n", mimDiseaseId , mimDiseaseName, cytoBand, mimGeneId, entrezGeneId, geneSymbol, seriesId));
                    writer.write(String.format("%s|%s|%s|%s|%s|%s|%s%n", mimDiseaseId , mimDiseaseName, cytoBand, mimGeneId, entrezGeneId, geneSymbol, seriesId));
                }
            }
        } catch (FileNotFoundException ex) {
            logger.error(null, ex);
            return ResourceOperationStatus.FAILURE;
        } catch (IOException ex) {
            logger.error(null, ex);
            return ResourceOperationStatus.FAILURE;
        }
        
        logger.info("Done parsing {}", inPath);
        return ResourceOperationStatus.SUCCESS;
    }

}
