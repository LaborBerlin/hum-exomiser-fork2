package de.charite.compbio.exomiser.core.prioritisers;

import java.util.ArrayList;

import de.charite.compbio.exomiser.core.model.Gene;
import de.charite.compbio.exomiser.core.prioritisers.util.DataMatrix;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.Map.Entry;
import javax.sql.DataSource;
import org.jblas.FloatMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filter genes according phenotypic similarity and to the random walk proximity
 * in the protein-protein interaction network.
 *
 * @author Damian Smedley <damian.smedley@sanger.ac.uk>
 * @author Jules Jacobsen <jules.jacobsen@sanger.ac.uk>
 */
public class ExomiserAllSpeciesPriority implements Priority {

    private static final Logger logger = LoggerFactory.getLogger(ExomiserAllSpeciesPriority.class);

    private static final PriorityType PRIORITY_TYPE = PriorityType.EXOMISER_ALLSPECIES_PRIORITY;
    
    private DataSource dataSource;

    /**
     * A list of messages that can be used to create a display in a HTML page or
     * elsewhere.
     */
    private List<String> messages = new ArrayList<String>();

    /**
     * The random walk matrix object
     */
    private final DataMatrix randomWalkMatrix;

//    private final PrioritiserService prioritiserService;
    private List<Integer> phenoGenes = new ArrayList<>();
    private List<String> phenoGeneSymbols = new ArrayList<>();
    private List<String> hpoIds;
    private String candidateGeneSymbol;
    private String diseaseId;

    private Map<Integer, Float> scores = new HashMap<>();
    private Map<Integer, Double> mouseScores = new HashMap<>();
    private Map<Integer, Double> humanScores = new HashMap<>();
    private Map<Integer, Double> fishScores = new HashMap<>();

    private Map<Integer, String> humanDiseases = new HashMap<>();
    private Map<Integer, String> mouseDiseases = new HashMap<>();
    private Map<Integer, String> fishDiseases = new HashMap<>();

    //TODO: move into external caches
    private Map<String, String> hpoTerms = new HashMap<>();
    private Map<String, String> mpoTerms = new HashMap<>();
    private Map<String, String> zpoTerms = new HashMap<>();
    private Map<String, String> diseaseTerms = new HashMap<>();

    private Map<Integer, HashMap<String, HashMap<String, HashMap<Float, String>>>> hpMpMatches = new HashMap<>();
    private Map<Integer, HashMap<String, HashMap<String, HashMap<Float, String>>>> hpHpMatches = new HashMap<>();
    private Map<Integer, HashMap<String, HashMap<String, HashMap<Float, String>>>> hpZpMatches = new HashMap<>();

    private float bestMaxScore = 0f;
    private float bestAvgScore = 0f;

    private boolean runPpi = false;
    private boolean runHuman = false;
    private boolean runMouse = false;
    private boolean runFish = false;

    /**
     * This is the matrix of similarities between the seeed genes and all genes
     * in the network, i.e., p<sub>infinity</sub>.
     */
    private FloatMatrix weightedHighQualityMatrix = new FloatMatrix();

    /**
     *
     * @param hpoIds
     * @param candidateGene
     * @param disease
     * @param exomiser2Params
     * @param randomWalkMatrix
     */
    public ExomiserAllSpeciesPriority(List<String> hpoIds, String candidateGene, String disease, String exomiser2Params, DataMatrix randomWalkMatrix) {
        this.hpoIds = hpoIds;
        this.candidateGeneSymbol = candidateGene;
        this.diseaseId = disease;
        this.randomWalkMatrix = randomWalkMatrix;
        parseParams(exomiser2Params);
    }

    private void parseParams(String exomiser2Params) {
        if (exomiser2Params.isEmpty()) {
            this.runPpi = true;
            this.runHuman = true;
            this.runMouse = true;
            this.runFish = true;
        } else {
            logger.info("Received extra params: " + exomiser2Params);
            String[] paramsArray = exomiser2Params.split(",");
            for (String param : paramsArray) {
                if (param.equals("ppi")) {
                    this.runPpi = true;
                } else if (param.equals("human")) {
                    this.runHuman = true;
                } else if (param.equals("mouse")) {
                    this.runMouse = true;
                } else if (param.equals("fish")) {
                    this.runFish = true;
                }
            }
        }
    }

    @Override
    public String getPriorityName() {
        return PRIORITY_TYPE.getCommandLineValue();
    }

    @Override
    public PriorityType getPriorityType() {
        return PRIORITY_TYPE;
    }

    /**
     * Prioritize a list of candidate {@link exomizer.exome.Gene Gene} objects
     * (the candidate genes have rare, potentially pathogenic variants).
     * <P>
     *
     * @param genes List of candidate genes.
     */
    @Override
    public void prioritizeGenes(List<Gene> genes) {

        setUpOntologyCaches();

        if (diseaseId != null && !diseaseId.isEmpty() && hpoIds.isEmpty()) {
            logger.info("Setting HPO IDs using disease annotations for {}", diseaseId);
            hpoIds = getHpoIdsForDisease(diseaseId);
        }

        hpHpMatches = makeHpHpMatches(hpoIds);
        hpMpMatches = makeHpMpMatches(hpoIds);
        hpZpMatches = makeHpZpMatches(hpoIds);

        if (runPpi) {
            weightedHighQualityMatrix = makeWeightedProteinInteractionMatrixFromHighQualityPhenotypeMatchedGenes(phenoGenes, scores);
        }

        List<ExomiserAllSpeciesPriorityResult> priorityResults = new ArrayList<>(genes.size());
        logger.info("Scoring genes...");
        for (Gene gene : genes) {
            ExomiserAllSpeciesPriorityResult priorityResult = makePrioritiserResultForGene(gene);
            gene.addPriorityResult(priorityResult);
            priorityResults.add(priorityResult);
        }

        /*
         * refactor all scores for genes that are not direct pheno-hits but in
         * PPI with them to a linear range
         */
        logger.info("Adjusting gene scores for non-pheno hits with protein-protein interactions");
        TreeMap<Float, List<Gene>> geneScoreMap = new TreeMap<>();
        for (Gene g : genes) {
            float geneScore = ((ExomiserAllSpeciesPriorityResult) g.getPriorityResult(PriorityType.EXOMISER_ALLSPECIES_PRIORITY)).getWalkerScore();
            if (geneScore == 0f) {
                continue;
            }
            if (geneScoreMap.containsKey(geneScore)) {
                List<Gene> geneScoreGeneList = geneScoreMap.get(geneScore);
                geneScoreGeneList.add(g);
            } else {
                List<Gene> geneScoreGeneList = new ArrayList<>();
                geneScoreGeneList.add(g);
                geneScoreMap.put(geneScore, geneScoreGeneList);
            }
        }
        //changed so when have only 2 genes say in filtered set 1st one will get 0.6 and second 0.3 rather than 0.3 and 0
        float rank = 0;
        for (Float score : geneScoreMap.descendingKeySet()) {
            List<Gene> geneScoreGeneList = geneScoreMap.get(score);
            int sharedHits = geneScoreGeneList.size();
            float adjustedRank = rank;
            if (sharedHits > 1) {
                adjustedRank = rank + (sharedHits / 2);
            }
            float newScore = 0.6f - 0.6f * (adjustedRank / genes.size());
            rank = rank + sharedHits;
            for (Gene gene : geneScoreGeneList) {
                //i.e. only overwrite phenotype-based score if PPI score is larger
                ExomiserAllSpeciesPriorityResult result = (ExomiserAllSpeciesPriorityResult) gene.getPriorityResult(PriorityType.EXOMISER_ALLSPECIES_PRIORITY);
                if (newScore > result.getScore()) {
                    result.setScore(newScore);
                }
            }
        }
        String message = makeStatsMessage(priorityResults, genes);
        messages.add(message);
    }

    private String makeStatsMessage(List<ExomiserAllSpeciesPriorityResult> priorityResults, List<Gene> genes) {
        int numGenesWithPhenotypeOrPpiData = 0;
        for (ExomiserAllSpeciesPriorityResult priorityResult : priorityResults) {
            if (priorityResult.getWalkerScore() > 0 || priorityResult.getHumanScore() > 0 || priorityResult.getMouseScore() > 0|| priorityResult.getFishScore() > 0 ) {
                numGenesWithPhenotypeOrPpiData++;
            }
        }
        int totalGenes = genes.size();
        return String.format("Phenotype and Protein-Protein Interaction evidence was available for %d of %d genes (%.1f%%)",
                numGenesWithPhenotypeOrPpiData, totalGenes, 100f * (numGenesWithPhenotypeOrPpiData / (float) totalGenes));
    }

    private ExomiserAllSpeciesPriorityResult makePrioritiserResultForGene(Gene gene) {
        String evidence = "";
        String humanPhenotypeEvidence = "";
        String mousePhenotypeEvidence = "";
        String fishPhenotypeEvidence = "";
        double score = 0f;
        double humanScore = 0f;
        double mouseScore = 0f;
        double fishScore = 0f;
        double walkerScore = 0f;
        // DIRECT PHENO HIT
        int entrezGeneId = gene.getEntrezGeneID();
        if (scores.containsKey(entrezGeneId)) {
            score = scores.get(entrezGeneId);
            // HUMAN
            if (humanScores.containsKey(entrezGeneId)) {
                humanScore = humanScores.get(entrezGeneId);
                String diseaseId = humanDiseases.get(entrezGeneId);
                String diseaseTerm = diseaseTerms.get(diseaseId);
                String diseaseLink = makeDiseaseLink(diseaseId, diseaseTerm);
                evidence = evidence + String.format("<dl><dt>Phenotypic similarity %.3f to %s associated with %s.</dt>", humanScores.get(gene.getEntrezGeneID()), diseaseLink, gene.getGeneSymbol());
                humanPhenotypeEvidence = makeBestPhenotypeMatchesHtml(entrezGeneId, humanDiseases, hpoIds, hpoTerms, hpoTerms, hpHpMatches);
                evidence = evidence + humanPhenotypeEvidence + "</dl>";
            }
            // MOUSE
            if (mouseScores.containsKey(entrezGeneId)) {
                evidence = evidence + String.format("<dl><dt>Phenotypic similarity %.3f to mouse mutant involving <a href=\"http://www.informatics.jax.org/searchtool/Search.do?query=%s\">%s</a>.</dt>", mouseScores.get(gene.getEntrezGeneID()), gene.getGeneSymbol(), gene.getGeneSymbol());
                mouseScore = mouseScores.get(entrezGeneId);
                mousePhenotypeEvidence = makeBestPhenotypeMatchesHtml(entrezGeneId, mouseDiseases, hpoIds, hpoTerms, mpoTerms, hpMpMatches);
                evidence = evidence + mousePhenotypeEvidence + "</dl>";
            }
            // FISH
            if (fishScores.containsKey(entrezGeneId)) {
                evidence = evidence + String.format("<dl><dt>Phenotypic similarity %.3f to zebrafish mutant involving <a href=\"http://zfin.org/action/quicksearch/query?query=%s\">%s</a>.</dt>", fishScores.get(gene.getEntrezGeneID()), gene.getGeneSymbol(), gene.getGeneSymbol());
                fishScore = fishScores.get(entrezGeneId);
                fishPhenotypeEvidence = makeBestPhenotypeMatchesHtml(entrezGeneId, fishDiseases, hpoIds, hpoTerms, zpoTerms, hpZpMatches);
                evidence = evidence + fishPhenotypeEvidence + "</dl>";
            }
        } 
        //INTERACTION WITH A HIGH QUALITY MOUSE/HUMAN PHENO HIT => 0 to 0.65 once scaled
        if (runPpi && randomWalkMatrix.containsGene(entrezGeneId) && !phenoGenes.isEmpty()) {
                int col_idx = getColumnIndexOfMostPhenotypicallySimilarGene(gene, phenoGenes);
                int row_idx = randomWalkMatrix.getRowIndexForGene(gene.getEntrezGeneID());
                walkerScore = weightedHighQualityMatrix.get(row_idx, col_idx);
                if (walkerScore <= 0.00001) {
                    walkerScore = 0f;
                } else {
                    //walkerScore = val;
                    String closestGene = phenoGeneSymbols.get(col_idx);
                    String thisGene = gene.getGeneSymbol();
                    //String stringDbImageLink = "http://string-db.org/api/image/networkList?identifiers=" + thisGene + "%0D" + closestGene + "&required_score=700&network_flavor=evidence&species=9606&limit=20";
                    String stringDbLink = "http://string-db.org/newstring_cgi/show_network_section.pl?identifiers=" + thisGene + "%0D" + closestGene + "&required_score=700&network_flavor=evidence&species=9606&limit=20";
                    entrezGeneId = phenoGenes.get(col_idx);
                    double phenoScore = scores.get(entrezGeneId);
                    // HUMAN
                    if (humanScores.containsKey(entrezGeneId)) {
                        double humanPPIScore = humanScores.get(entrezGeneId);
                        String diseaseId = humanDiseases.get(entrezGeneId);
                        String diseaseTerm = diseaseTerms.get(diseaseId);
                        String diseaseLink = makeDiseaseLink(diseaseId, diseaseTerm);
                        evidence = evidence + String.format("<dl><dt>Proximity in <a href=\"%s\">interactome to %s</a> with score %s and phenotypic similarity to %s associated with %s.</dt>", stringDbLink, closestGene, humanPPIScore, diseaseLink, closestGene);
                        humanPhenotypeEvidence = makeBestPhenotypeMatchesHtml(entrezGeneId, humanDiseases, hpoIds, hpoTerms, hpoTerms, hpHpMatches);
                        evidence = evidence + humanPhenotypeEvidence + "</dl>";
                    }
                    // MOUSE
                    if (mouseScores.containsKey(entrezGeneId)) {
                        evidence = evidence + String.format("<dl><dt>Proximity in <a href=\"%s\">interactome to %s</a> and phenotypic similarity to mouse mutant of %s.</dt>", stringDbLink, closestGene, closestGene);
                        mousePhenotypeEvidence = makeBestPhenotypeMatchesHtml(entrezGeneId, mouseDiseases, hpoIds, hpoTerms, mpoTerms, hpMpMatches);
                        evidence = evidence + mousePhenotypeEvidence + "</dl>";

                    }
                    // FISH
                    if (fishScores.containsKey(entrezGeneId)) {
                        evidence = evidence + String.format("<dl><dt>Proximity in <a href=\"%s\">interactome to %s</a> and phenotypic similarity to fish mutant of %s.</dt><dt>Best Phenotype Matches:</dt>", stringDbLink, closestGene, closestGene);
                        fishPhenotypeEvidence = makeBestPhenotypeMatchesHtml(entrezGeneId, fishDiseases, hpoIds, hpoTerms, zpoTerms, hpZpMatches);
                        evidence = evidence + fishPhenotypeEvidence + "</dl>";
                    }
                }
        } 
        // NO PHENO HIT OR PPI INTERACTION
        if (evidence.isEmpty()) {
            evidence = "<dl><dt>No phenotype or PPI evidence</dt></dl>";
        }
        return new ExomiserAllSpeciesPriorityResult(score, evidence, humanPhenotypeEvidence, mousePhenotypeEvidence,
                fishPhenotypeEvidence, humanScore, mouseScore, fishScore, walkerScore);
    }

    private String makeBestPhenotypeMatchesHtml(int entrezGeneId, Map<Integer, String> models, List<String> hpoIds, Map<String, String> hpoTerms, Map<String, String> otherTerms, Map<Integer, HashMap<String, HashMap<String, HashMap<Float, String>>>> phenotypeMatches) {
        String model = models.get(entrezGeneId);
        StringBuilder stringBuilder = new StringBuilder("<dt>Best Phenotype Matches:</dt>");
        if (phenotypeMatches.containsKey(entrezGeneId) && phenotypeMatches.get(entrezGeneId).containsKey(model)) {
            for (String hpId : hpoIds) {
                String hpTerm = hpoTerms.get(hpId);
                if (phenotypeMatches.get(entrezGeneId).get(model).containsKey(hpId)) {
                    Set<Float> hpIdScores = phenotypeMatches.get(entrezGeneId).get(model).get(hpId).keySet();
                    for (float hpIdScore : hpIdScores) {
                        String mpIdHit = phenotypeMatches.get(entrezGeneId).get(model).get(hpId).get(hpIdScore);
                        String mpTermHit = otherTerms.get(mpIdHit);
                        stringBuilder.append(String.format("<dd>%s (%s) - %s (%s)</dd>", hpTerm, hpId, mpTermHit, mpIdHit));
                    }
                } else {
                    stringBuilder.append(String.format("<dd>%s (%s) - </dd>", hpTerm, hpId));
                }
            }
        }
        return stringBuilder.toString();
    }

    private String makeDiseaseLink(String diseaseId, String diseaseTerm) {
        String[] databaseNameAndIdentifier = diseaseId.split(":");
        String databaseName = databaseNameAndIdentifier[0];
        String id = databaseNameAndIdentifier[1];
        if (databaseName.equals("OMIM")) {
            return "<a href=\"http://www.omim.org/" + id + "\">" + diseaseTerm + "</a>";
        } else {
            return "<a href=\"http://www.orpha.net/consor/cgi-bin/OC_Exp.php?lng=en&Expert=" + id + "\">" + diseaseTerm + "</a>";
        }
    }

    //TODO - this shouldn' exist. runDynamicQuery should have two variants - one for human the other for non-human
    private enum Species {
        HUMAN, MOUSE, FISH;
    }

    private Map<Integer, HashMap<String, HashMap<String, HashMap<Float, String>>>> makeHpHpMatches(List<String> hpoIds) {
        //TODO: this must always run in order that the best score is set - refactor this so that the behaviour of runDynamicQuery
        //is consistent with the mouse and fish
        // Human
        logger.info("Fetching HP-HP scores...");
        String mappingQuery = "SELECT hp_id_hit, score FROM hp_hp_mappings M WHERE M.hp_id = ?";
        String annotationQuery = String.format("SELECT H.disease_id, hp_id, gene_id, human_gene_symbol FROM human2mouse_orthologs hm, disease_hp M, disease H WHERE hm.entrez_id=H.gene_id AND M.disease_id=H.disease_id");
        return runDynamicQuery(mappingQuery, annotationQuery, hpoIds, Species.HUMAN);
    }

    private Map<Integer, HashMap<String, HashMap<String, HashMap<Float, String>>>> makeHpMpMatches(List<String> hpoIds) {
        // Mouse
        if (runMouse) {
            logger.info("Fetching HP-MP scores...");
            String mappingQuery = "SELECT mp_id, score FROM hp_mp_mappings M WHERE M.hp_id = ?";
            String annotationQuery = "SELECT mouse_model_id, mp_id, entrez_id, human_gene_symbol, M.mgi_gene_id, M.mgi_gene_symbol FROM mgi_mp M, human2mouse_orthologs H WHERE M.mgi_gene_id=H.mgi_gene_id and human_gene_symbol != 'null'";
            return runDynamicQuery(mappingQuery, annotationQuery, hpoIds, Species.MOUSE);
        } else {
            return Collections.emptyMap();
        }
    }

    private Map<Integer, HashMap<String, HashMap<String, HashMap<Float, String>>>> makeHpZpMatches(List<String> hpoIds) {
        // Fish
        if (runFish) {
            logger.info("Fetching HP-ZP scores...");
            String mappingQuery = "SELECT zp_id, score FROM hp_zp_mappings M WHERE M.hp_id = ?";
            String annotationQuery = "SELECT zfin_model_id, zp_id, entrez_id, human_gene_symbol, M.zfin_gene_id, M.zfin_gene_symbol FROM zfin_zp M, human2fish_orthologs H WHERE M.zfin_gene_id=H.zfin_gene_id and human_gene_symbol != 'null'";
            return runDynamicQuery(mappingQuery, annotationQuery, hpoIds, Species.FISH);
        } else {
            return Collections.emptyMap();
        }
    }

    private Map<Integer, HashMap<String, HashMap<String, HashMap<Float, String>>>> runDynamicQuery(String mappingQuery, String findAnnotationQuery, List<String> hpoIds, Species species) {

        Set<String> hpIdsWithPhenotypeMatch = new LinkedHashSet<>();
        Map<String, Float> bestMappedTermScore = new HashMap<>();
        Map<String, String> bestMappedTermMpId = new HashMap<>();
        Map<String, Integer> knownMps = new HashMap<>();

        Map<String, Float> mappedTerms = new HashMap<>();
        for (String hpId : hpoIds) {
            try (Connection connection = dataSource.getConnection()) {
                PreparedStatement findMappingStatement = connection.prepareStatement(mappingQuery);
                findMappingStatement.setString(1, hpId);
                ResultSet rs = findMappingStatement.executeQuery();
                while (rs.next()) {
                    String mpId = rs.getString(1);
                    knownMps.put(mpId, 1);
                    StringBuilder hashKey = new StringBuilder();
                    hashKey.append(hpId);
                    hashKey.append(mpId);
                    float score = rs.getFloat("score");
                    mappedTerms.put(hashKey.toString(), score);
                    if (species == Species.HUMAN && hpId.equals(mpId)) {
                        addBestMappedTerm(bestMappedTermScore, hpId, score, bestMappedTermMpId, mpId);
                        //for some hp terms e.g. HP we won't have the self hit but still want to flag found
                        hpIdsWithPhenotypeMatch.add(hpId);
                    } else {
                        if (bestMappedTermScore.containsKey(hpId)) {
                            if (score > bestMappedTermScore.get(hpId)) {
                                addBestMappedTerm(bestMappedTermScore, hpId, score, bestMappedTermMpId, mpId);
                            }
                        } else {
                            addBestMappedTerm(bestMappedTermScore, hpId, score, bestMappedTermMpId, mpId);
                            hpIdsWithPhenotypeMatch.add(hpId);
                        }
                    }
                }
            } catch (SQLException e) {
                logger.error("Problem setting up SQL query: {}", mappingQuery, e);
            }
        }
        logger.debug("Phenotype matches {} for {}", mappedTerms, species);
        for (Entry<String, String> bestMappedHpIdToOtherId : bestMappedTermMpId.entrySet()) {
            String hpId = bestMappedHpIdToOtherId.getKey();
            logger.debug("Best match: {}-{}={}", hpId, bestMappedHpIdToOtherId.getValue(), bestMappedTermScore.get(hpId));
        }

        if (species == Species.HUMAN) {
            calculateBestScoresFromHumanPhenotypes(hpIdsWithPhenotypeMatch, bestMappedTermScore, bestMappedTermMpId, mappedTerms);
        }
        //TODO: needed here or do before? 
        if (species == Species.HUMAN && !runHuman) {
            return Collections.emptyMap();
        }

        // calculate score for this gene
        Map<Integer, HashMap<String, HashMap<String, HashMap<Float, String>>>> hpMatches = new HashMap<>();
        logger.info("Fetching disease/model phenotype annotations and human-{} gene orthologs", species);
        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement findAnnotationStatement = connection.prepareStatement(findAnnotationQuery);
            ResultSet rs = findAnnotationStatement.executeQuery();
            while (rs.next()) {
                String hit = rs.getString(1);
                String mpIds = rs.getString(2);
                int entrezId = rs.getInt(3);
                String humanGeneSymbol = rs.getString(4);
                String[] mpInitial = mpIds.split(",");
                List<String> mpList = new ArrayList<>();
                for (String mpid : mpInitial) {
                    if (knownMps.get(mpid) != null) {
                        mpList.add(mpid);
                    }
                }
                String[] mps = new String[mpList.size()];
                mpList.toArray(mps);

                int rowColumnCount = hpIdsWithPhenotypeMatch.size() + mps.length;
                float maxScore = 0f;
                float sumBestHitRowsColumnsScore = 0f;

                for (String hpId : hpIdsWithPhenotypeMatch) {
                    float bestScore = 0f;
                    for (String mpId : mps) {
                        String hashKey = hpId + mpId;
                        if (mappedTerms.containsKey(hashKey)) {
                            float score = mappedTerms.get(hashKey);
                            // identify best match                                                                                                                                                                 
                            if (score > bestScore) {
                                bestScore = score;
                            }
                            if (score > 0) {
                                if (hpMatches.get(entrezId) == null) {
                                    hpMatches.put(entrezId, new HashMap<String, HashMap<String, HashMap<Float, String>>>());
                                    hpMatches.get(entrezId).put(hit, new HashMap<String, HashMap<Float, String>>());
                                    hpMatches.get(entrezId).get(hit).put(hpId, new HashMap<Float, String>());
                                    hpMatches.get(entrezId).get(hit).get(hpId).put(score, mpId);
                                } else if (hpMatches.get(entrezId).get(hit) == null) {
                                    hpMatches.get(entrezId).put(hit, new HashMap<String, HashMap<Float, String>>());
                                    hpMatches.get(entrezId).get(hit).put(hpId, new HashMap<Float, String>());
                                    hpMatches.get(entrezId).get(hit).get(hpId).put(score, mpId);
                                } else if (hpMatches.get(entrezId).get(hit).get(hpId) == null) {
                                    hpMatches.get(entrezId).get(hit).put(hpId, new HashMap<Float, String>());
                                    hpMatches.get(entrezId).get(hit).get(hpId).put(score, mpId);
                                } else if (hpMatches.get(entrezId).get(hit).get(hpId).keySet().iterator().next() < score) {
                                    hpMatches.get(entrezId).get(hit).get(hpId).clear();
                                    hpMatches.get(entrezId).get(hit).get(hpId).put(score, mpId);
                                }
                            }
                        }
                    }
                    if (bestScore != 0) {
                        sumBestHitRowsColumnsScore += bestScore;

                        if (bestScore > maxScore) {
                            maxScore = bestScore;
                        }
                    }
                }
                // Reciprocal hits                                                                                                                                                                                 
                for (String mpId : mps) {
                    float bestScore = 0f;
                    for (String hpId : hpIdsWithPhenotypeMatch) {
                        String hashKey = hpId + mpId;
                        if (mappedTerms.containsKey(hashKey)) {
                            float score = mappedTerms.get(hashKey);
                            // identify best match                                                                                                                                                                 
                            if (score > bestScore) {
                                bestScore = score;
                            }
                            if (score > 0) {
                                if (hpMatches.get(entrezId) == null) {
                                    hpMatches.put(entrezId, new HashMap<String, HashMap<String, HashMap<Float, String>>>());
                                    hpMatches.get(entrezId).put(hit, new HashMap<String, HashMap<Float, String>>());
                                    hpMatches.get(entrezId).get(hit).put(hpId, new HashMap<Float, String>());
                                    hpMatches.get(entrezId).get(hit).get(hpId).put(score, mpId);
                                } else if (hpMatches.get(entrezId).get(hit) == null) {
                                    hpMatches.get(entrezId).put(hit, new HashMap<String, HashMap<Float, String>>());
                                    hpMatches.get(entrezId).get(hit).put(hpId, new HashMap<Float, String>());
                                    hpMatches.get(entrezId).get(hit).get(hpId).put(score, mpId);
                                } else if (hpMatches.get(entrezId).get(hit).get(hpId) == null) {
                                    hpMatches.get(entrezId).get(hit).put(hpId, new HashMap<Float, String>());
                                    hpMatches.get(entrezId).get(hit).get(hpId).put(score, mpId);
                                } else if (hpMatches.get(entrezId).get(hit).get(hpId).keySet().iterator().next() < score) {
                                    hpMatches.get(entrezId).get(hit).get(hpId).clear();
                                    hpMatches.get(entrezId).get(hit).get(hpId).put(score, mpId);
                                }
                            }
                        }
                    }
                    if (bestScore != 0) {
                        sumBestHitRowsColumnsScore += bestScore;
                        if (bestScore > maxScore) {
                            maxScore = bestScore;
                        }
                    }
                }
                // calculate combined score
                if (sumBestHitRowsColumnsScore != 0) {
                    double avgBestHitRowsColumnsScore = sumBestHitRowsColumnsScore / rowColumnCount;
                    double combinedScore = 50 * (maxScore / bestMaxScore
                            + avgBestHitRowsColumnsScore / bestAvgScore);
                    if (combinedScore > 100) {
                        combinedScore = 100;
                    }
                    double score = combinedScore / 100;
                    /*
                     * Adjust human score as a hit that is 60% of the perfect
                     * (identical) HPO match is a much better match than
                     * something that is 60% of the perfect mouse match -
                     * imperfect HP-MP mapping
                     */
//                    if (species.equals("human")) {
//                        score = score + ((1 - score) / 2);
//                    }
//                    // adjust fish score - over-scoring at moment as even a perfect fish match is much worse than the mouse and human hits
//                    if (species.equals("fish")) {
//                        score = score - ((score) / 2);
//                    }
                    // code to catch hit to known disease-gene association for purposes of benchmarking i.e to simulate novel gene discovery performance
                    if ((hit == null ? diseaseId == null : hit.equals(diseaseId))
                            && (humanGeneSymbol == null ? candidateGeneSymbol == null : humanGeneSymbol.equals(candidateGeneSymbol))) {
                        //System.out.println("FOUND self hit " + disease + ":"+candGene);
                        // Decided does not make sense to build PPI to candidate gene unless another good disease/mouse/fish hit exists for it
//                        if (scores.get(entrez) != null) {
//                            phenoGenes.add(entrez);
//                            phenoGeneSymbols.add(humanGene);
//                        }
                    } else {
                        // normal behaviour when not trying to exclude candidate gene to simulate novel gene disovery in benchmarking
                        // only build PPI network for high qual hits
                        if (score > 0.6) {
                            phenoGenes.add(entrezId);
                            phenoGeneSymbols.add(humanGeneSymbol);
                        }
                        if (!scores.containsKey(entrezId) || score > scores.get(entrezId)) {
                            scores.put(entrezId, (float) score);
                        }
                        if (species == Species.HUMAN) {
                            addScoreIfAbsentOrBetter(entrezId, score, hit, humanScores, humanDiseases);
                        }
                        if (species == Species.MOUSE) {
                            addScoreIfAbsentOrBetter(entrezId, score, hit, mouseScores, mouseDiseases);
                        }
                        if (species == Species.FISH) {
                            addScoreIfAbsentOrBetter(entrezId, score, hit, fishScores, fishDiseases);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Problem setting up SQL query: {}", findAnnotationQuery, e);
        }
        return hpMatches;
    }

    private void calculateBestScoresFromHumanPhenotypes(Set<String> hpIdsWithPhenotypeMatch, Map<String, Float> best_mapped_term_score, Map<String, String> best_mapped_term_mpid, Map<String, Float> mapped_terms) {
        // calculate perfect model scores for human
        float sum_best_score = 0f;
        // loop over each hp id should start here
        for (String hpId : hpIdsWithPhenotypeMatch) {
            if (best_mapped_term_score.containsKey(hpId)) {
                float hp_score = best_mapped_term_score.get(hpId);
                // add in scores for best match for the HP term
                sum_best_score += hp_score;
                if (hp_score > bestMaxScore) {
                    bestMaxScore = hp_score;
                }
                //logger.info("ADDING SCORE FOR " + hpid + " TO " + best_mapped_term_mpid.get(hpid) + " WITH SCORE " + hp_score + ", SUM NOW " + sum_best_score + ", MAX NOW " + this.best_max_score);
                // add in MP-HP hits
                String mpId = best_mapped_term_mpid.get(hpId);
                float best_score = 0f;
                for (String hpId2 : hpIdsWithPhenotypeMatch) {
                    StringBuilder hashKey = new StringBuilder();
                    hashKey.append(hpId2);
                    hashKey.append(mpId);
                    if (mapped_terms.get(hashKey.toString()) != null && mapped_terms.get(hashKey.toString()) > best_score) {
                        best_score = mapped_terms.get(hashKey.toString());
                    }
                }
                // add in scores for best match for the MP term
                sum_best_score += best_score;
                //logger.info("ADDING RECIPROCAL SCORE FOR " + mpid + " WITH SCORE " + best_score + ", SUM NOW " + sum_best_score + ", MAX NOW " + this.best_max_score);
                if (best_score > bestMaxScore) {
                    bestMaxScore = best_score;
                }
            }
        }
        bestAvgScore = sum_best_score / (2 * hpIdsWithPhenotypeMatch.size());
    }

    private void addBestMappedTerm(Map<String, Float> best_mapped_term_score, String hpid, float score, Map<String, String> best_mapped_term_mpid, String mp_id) {
        best_mapped_term_score.put(hpid, score);
        best_mapped_term_mpid.put(hpid, mp_id);
    }

    private void addScoreIfAbsentOrBetter(int entrez, double score, String hit, Map<Integer, Double> geneToScoreMap, Map<Integer, String> geneToDiseaseMap) {
        if (geneToScoreMap.get(entrez) == null || score > geneToScoreMap.get(entrez)) {
            geneToScoreMap.put(entrez, score);
            geneToDiseaseMap.put(entrez, hit);
        }
    }

    //todo: If this returned a DataMatrix things might be a bit more convienent later on... 
    private FloatMatrix makeWeightedProteinInteractionMatrixFromHighQualityPhenotypeMatchedGenes(List<Integer> phenoGenes, Map<Integer, Float> scores) {
        logger.info("Making weighted-score Protein-Protein interaction sub-matrix from high quality phenotypic gene matches...");
        int rows = randomWalkMatrix.getMatrix().getRows();
        int cols = phenoGenes.size();
        FloatMatrix highQualityPpiMatrix = FloatMatrix.zeros(rows, cols);
        int c = 0;
        for (Integer seedGeneEntrezId : phenoGenes) {
            if (randomWalkMatrix.containsGene(seedGeneEntrezId)) {
                FloatMatrix column = randomWalkMatrix.getColumnMatrixForGene(seedGeneEntrezId);
                // weight column by phenoScore 
                float score = scores.get(seedGeneEntrezId);
                column = column.mul(score);
                highQualityPpiMatrix.putColumn(c, column);
            }
            c++;
        }
        return highQualityPpiMatrix;
    }

    /**
     * This function retrieves the random walk similarity score for the gene
     *
     * @param gene for which the random walk score is to be retrieved
     */
    private int getColumnIndexOfMostPhenotypicallySimilarGene(Gene gene, List<Integer> phenotypicallySimilarGeneIds) {
        int geneIndex = randomWalkMatrix.getRowIndexForGene(gene.getEntrezGeneID());
        int columnIndex = 0;
        double bestScore = 0;
        int bestHitIndex = 0;
        for (Integer similarGeneEntrezId : phenotypicallySimilarGeneIds) {
            if (!randomWalkMatrix.containsGene(similarGeneEntrezId)) {
                columnIndex++;
                continue;
            } else if (similarGeneEntrezId == gene.getEntrezGeneID()) {//avoid self-hits now are testing genes with direct pheno-evidence as well
                columnIndex++;
                continue;
            } else {
                double cellScore = weightedHighQualityMatrix.get(geneIndex, columnIndex);
                if (cellScore > bestScore) {
                    bestScore = cellScore;
                    bestHitIndex = columnIndex;
                }
                columnIndex++;
            }
        }
        return bestHitIndex;
    }

    /**
     * @return list of messages representing process, result, and if any, errors
     * of score filtering.
     */
    @Override
    public List<String> getMessages() {
        return this.messages;
    }

    /**
     * This causes a summary of RW prioritization to appear in the HTML output
     * of the exomizer
     *
     * @return
     */
    @Override
    public boolean displayInHTML() {
        return true;
    }

    /**
     * @return HTML code for displaying the HTML output of the Exomizer.
     */
    @Override
    public String getHTMLCode() {
        if (messages.isEmpty()) {
            return "Error initializing Random Walk matrix";
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("<ul>\n");
            for (String m : messages) {
                sb.append(String.format("<li>%s</li>\n", m));
            }
            sb.append("</ul>\n");
            return sb.toString();
        }
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private void setUpOntologyCaches() {
        hpoTerms = getHpoTermsCache();
        mpoTerms = getMpoTermsCache();
        zpoTerms = getZpoTermsCache();
        diseaseTerms = getDiseaseTermsCache();
    }

    private Map<String, String> getHpoTermsCache() {
        Map<String, String> termsCache = makeGenericOntologyTermCache("select id, lcname from hpo");
        logger.info("HPO cache initialised with {} terms", termsCache.size());
        return termsCache;
    }

    private Map<String, String> getMpoTermsCache() {
        Map<String, String> termsCache = makeGenericOntologyTermCache("SELECT mp_id, mp_term FROM mp");
        logger.info("MPO cache initialised with {} terms", termsCache.size());
        return termsCache;
    }

    private Map<String, String> getZpoTermsCache() {
        Map<String, String> termsCache = makeGenericOntologyTermCache("SELECT zp_id, zp_term FROM zp");
        logger.info("ZPO cache initialised with {} terms", termsCache.size());
        return termsCache;
    }

    private Map<String, String> getDiseaseTermsCache() {
        Map<String, String> termsCache = makeGenericOntologyTermCache("SELECT disease_id, diseasename FROM disease");
        logger.info("Disease cache initialised with {} terms", termsCache.size());
        return termsCache;
    }

    private Map<String, String> makeGenericOntologyTermCache(String selectZpoQuery) {
        Map<String, String> termsCache = new HashMap();
        try {
            try (Connection connection = dataSource.getConnection()) {
                PreparedStatement ontologyTermsStatement = connection.prepareStatement(selectZpoQuery);
                ResultSet rs = ontologyTermsStatement.executeQuery();
                while (rs.next()) {
                    String id = rs.getString(1);
                    String term = rs.getString(2);
                    id = id.trim();
                    termsCache.put(id, term);
                }
            }
        } catch (SQLException e) {
            logger.error("Unable to execute query '{}' for ontology terms cache", selectZpoQuery, e);
        }
        return termsCache;
    }

    /**
     * Set hpo_ids variable based on the entered disease
     */
    private List<String> getHpoIdsForDisease(String disease) {
        String hpoListString = "";
        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement hpoIdsStatement = connection.prepareStatement("SELECT hp_id FROM disease_hp WHERE disease_id = ?");
            hpoIdsStatement.setString(1, disease);
            ResultSet rs = hpoIdsStatement.executeQuery();
            rs.next();
            hpoListString = rs.getString(1);
        } catch (SQLException e) {
            logger.error("Unable to retrieve HPO terms for disease {}", disease, e);
        }
        List<String> diseaseHpoIds = parseHpoIdListFromString(hpoListString);
        logger.info("{} HPO ids retrieved for disease {} - {}", diseaseHpoIds.size(), disease, diseaseHpoIds);
        return diseaseHpoIds;
    }

    private List<String> parseHpoIdListFromString(String hpoIdsString) {
        String[] hpoArray = hpoIdsString.split(",");
        List<String> hpoIdList = new ArrayList<>();
        for (String string : hpoArray) {
            hpoIdList.add(string.trim());
        }
        return hpoIdList;
    }

}
