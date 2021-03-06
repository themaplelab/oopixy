package at.ac.tuwien.infosys.www.pixy;

import at.ac.tuwien.infosys.www.pixy.analysis.dependency.DependencyAnalysis;
import at.ac.tuwien.infosys.www.pixy.analysis.dependency.Sink;
import at.ac.tuwien.infosys.www.pixy.analysis.dependency.graph.*;
import at.ac.tuwien.infosys.www.pixy.automaton.Automaton;
import at.ac.tuwien.infosys.www.pixy.automaton.Transition;
import at.ac.tuwien.infosys.www.pixy.conversion.AbstractTacPlace;
import at.ac.tuwien.infosys.www.pixy.conversion.TacActualParameter;
import at.ac.tuwien.infosys.www.pixy.conversion.TacFunction;
import at.ac.tuwien.infosys.www.pixy.conversion.cfgnodes.AbstractCfgNode;
import at.ac.tuwien.infosys.www.pixy.conversion.cfgnodes.CallBuiltinFunction;
import at.ac.tuwien.infosys.www.pixy.conversion.cfgnodes.CallPreparation;
import at.ac.tuwien.infosys.www.pixy.conversion.cfgnodes.CallUnknownFunction;
import at.ac.tuwien.infosys.www.pixy.sanitation.AbstractSanitationAnalysis;
import at.ac.tuwien.infosys.www.pixy.transduction.MyTransductions;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.*;

/**
 * SQL Injection detection.
 *
 * Note: This class will be instantiated via reflection in GenericTaintAnalysis.createAnalysis. It is registered in
 * MyOptions.VulnerabilityAnalysisInformation.
 *
 * @author Nenad Jovanovic <enji@seclab.tuwien.ac.at>
 */
public class SqlAnalysis extends AbstractVulnerabilityAnalysis {
    /** flag indicating whether to use transducers (are still unstable) */
    private boolean useTransducers = false;
    private List<Integer> lineNumbersOfVulnerabilities;
    private int dependencyGraphCount;
    private int vulnerabilityCount;

    public SqlAnalysis(DependencyAnalysis dependencyAnalysis) {
        super(dependencyAnalysis);
        this.getIsTainted = !MyOptions.optionI;
    }

    /**
     * Detects vulnerabilities and returns a list with the line numbers of the detected vulnerabilities.
     *
     * @return the line numbers of the detected vulnerabilities
     */
    public List<Integer> detectVulnerabilities() {
        System.out.println();
        System.out.println("*****************");
        System.out.println("SQL Analysis BEGIN");
        System.out.println("*****************");
        System.out.println();

        lineNumbersOfVulnerabilities = new LinkedList<>();

        List<Sink> sinks = this.collectSinks();
        Collections.sort(sinks);

        System.out.println("Number of sinks: " + sinks.size());
        System.out.println();

        System.out.println("SQL Analysis Output");
        System.out.println("--------------------");
        System.out.println();

        String fileName = MyOptions.entryFile.getName();

        dependencyGraphCount = 0;
        vulnerabilityCount = 0;
        for (Sink sink : sinks) {
            detectVulnerabilitiesForSink(fileName, sink);
        }

        // initial sink count and final graph count may differ (e.g., if some sinks
        // are not reachable)
        if (MyOptions.optionV) {
            System.out.println("Total Graph Count: " + dependencyGraphCount);
        }
        System.out.println("Total Vuln Count: " + vulnerabilityCount);

        System.out.println();
        System.out.println("*****************");
        System.out.println("SQL Analysis END");
        System.out.println("*****************");
        System.out.println();

        return lineNumbersOfVulnerabilities;
    }

    private void detectVulnerabilitiesForSink(String fileName, Sink sink) {
        Collection<DependencyGraph> dependencyGraphs = dependencyAnalysis.getDependencyGraphsForSink(sink);

        for (DependencyGraph dependencyGraph : dependencyGraphs) {
            dependencyGraphCount++;

            String graphNameBase = "sql_" + fileName + "_" + dependencyGraphCount;

            DependencyGraph sqlGraph = new DependencyGraph(dependencyGraph);
            AbstractCfgNode cfgNode = dependencyGraph.getRootNode().getCfgNode();

            dependencyGraph.dumpDot(graphNameBase + "_dep", MyOptions.graphPath, dependencyGraph.getUninitializedNodes(), this.vulnerabilityAnalysisInformation);

            Automaton automaton = this.toAutomaton(sqlGraph, dependencyGraph);

            boolean tainted = false;
            if (automaton.hasDirectlyTaintedTransitions()) {
                System.out.println("directly tainted!");
                tainted = true;
            }
            if (automaton.hasIndirectlyTaintedTransitions()) {
                if (automaton.hasDangerousIndirectTaint()) {
                    System.out.println("indirectly tainted and dangerous!");
                    tainted = true;
                }
            }
            if (tainted) {
                vulnerabilityCount++;
                lineNumbersOfVulnerabilities.add(cfgNode.getOriginalLineNumber());

                System.out.println("- " + cfgNode.getLoc());
                System.out.println("- Graphs: sql" + dependencyGraphCount);
            }

            // Ff we have detected a vulnerability, also dump a reduced SQL dependency graph.
            if (tainted) {
                DependencyGraph relevantSubgraph = this.getRelevantSubgraph(dependencyGraph);
                Map<UninitializedNode, InitialTaint> dangerousUninitializedNodes
                    = this.findDangerousUninitializedNodes(relevantSubgraph);
                if (!dangerousUninitializedNodes.isEmpty()) {
                    if (dangerousUninitializedNodes.values().contains(InitialTaint.ALWAYS)) {
                        System.out.println("- unconditional");
                    } else {
                        System.out.println("- conditional on register_globals=on");
                    }
                    relevantSubgraph.reduceWithLeaves(dangerousUninitializedNodes.keySet());
                    Set<? extends AbstractNode> fillUs;
                    if (MyOptions.option_V) {
                        relevantSubgraph.removeTemporaries();
                        fillUs = relevantSubgraph.removeUninitializedNodes();
                    } else {
                        fillUs = dangerousUninitializedNodes.keySet();
                    }
                    relevantSubgraph.dumpDot(graphNameBase + "_min", MyOptions.graphPath, fillUs, this.vulnerabilityAnalysisInformation);
                }

                System.out.println();
            }

            this.dumpDotAuto(automaton, graphNameBase + "_auto", MyOptions.graphPath);
        }
    }

    /**
     * Alternative to detectVulnerabilities.
     *
     * Returns those DependencyGraphs for which a vulnerability was detected.
     *
     * @return
     */
    public VulnerabilityInformation detectAlternative() {
        VulnerabilityInformation dependencyGraphsWithVulnerabilities = new VulnerabilityInformation();

        // collect sinks
        List<Sink> sinks = this.collectSinks();
        Collections.sort(sinks);

        int graphCount = 0;
        int totalPathCount = 0;
        int basicPathCount = 0;
        int hasCustomSanitationCount = 0;
        int customSanitationThrownAwayCount = 0;
        for (Sink sink : sinks) {
            Collection<DependencyGraph> dependencyGraphs = dependencyAnalysis.getDependencyGraphsForSink(sink);

            for (DependencyGraph dependencyGraph : dependencyGraphs) {
                graphCount++;

                DependencyGraph workGraph = new DependencyGraph(dependencyGraph);
                Automaton automaton = this.toAutomaton(workGraph, dependencyGraph);

                boolean tainted = false;
                if (automaton.hasDirectlyTaintedTransitions()) {
                    tainted = true;
                }
                if (automaton.hasIndirectlyTaintedTransitions()) {
                    if (automaton.hasDangerousIndirectTaint()) {
                        tainted = true;
                    }
                }
                if (tainted) {
                    DependencyGraph relevantSubgraph = this.getRelevantSubgraph(dependencyGraph);
                    Map<UninitializedNode, InitialTaint> dangerousUninitializedNodes
                        = this.findDangerousUninitializedNodes(relevantSubgraph);
                    relevantSubgraph.reduceWithLeaves(dangerousUninitializedNodes.keySet());

                    dependencyGraphsWithVulnerabilities.addDepGraph(dependencyGraph, relevantSubgraph);
                }

                if (MyOptions.countPaths) {
                    int pathNum = dependencyGraph.countPaths();
                    totalPathCount += pathNum;
                    if (tainted) {
                        basicPathCount += pathNum;
                    }
                }

                if (!AbstractSanitationAnalysis.findCustomSanit(dependencyGraph).isEmpty()) {
                    hasCustomSanitationCount++;
                    if (!tainted) {
                        customSanitationThrownAwayCount++;
                    }
                }
            }
        }

        dependencyGraphsWithVulnerabilities.setInitialGraphCount(graphCount);
        dependencyGraphsWithVulnerabilities.setTotalPathCount(totalPathCount);
        dependencyGraphsWithVulnerabilities.setBasicPathCount(basicPathCount);
        dependencyGraphsWithVulnerabilities.setCustomSanitCount(hasCustomSanitationCount);
        dependencyGraphsWithVulnerabilities.setCustomSanitThrownAwayCount(customSanitationThrownAwayCount);

        return dependencyGraphsWithVulnerabilities;
    }

//  ********************************************************************************

    // returns the automaton representation of the given dependency graph;
    // is done by decorating the nodes of the graph with automata bottom-up,
    // and returning the automaton that eventually decorates the root;
    // BEWARE: this also eliminates cycles!
    Automaton toAutomaton(DependencyGraph dependencyGraph, DependencyGraph origDependencyGraph) {
        dependencyGraph.eliminateCycles();
        AbstractNode root = dependencyGraph.getRootNode();
        Map<AbstractNode, Automaton> deco = new HashMap<>();
        Set<AbstractNode> visited = new HashSet<>();
        this.decorate(root, deco, visited, dependencyGraph, origDependencyGraph);
        Automaton rootDeco = deco.get(root).clone();

        return rootDeco;
    }

//  ********************************************************************************

    // decorates the given node (and all its successors) with an automaton
    private void decorate(
        AbstractNode node, Map<AbstractNode, Automaton> deco,
        Set<AbstractNode> visited, DependencyGraph dependencyGraph, DependencyGraph origDependencyGraph
    ) {
        visited.add(node);

        // if this node has successors, decorate them first (if not done yet)
        List<AbstractNode> successors = dependencyGraph.getSuccessors(node);
        if (successors != null && !successors.isEmpty()) {
            for (AbstractNode succ : successors) {
                if (!visited.contains(succ) && deco.get(succ) == null) {
                    decorate(succ, deco, visited, dependencyGraph, origDependencyGraph);
                }
            }
        }

        // now that all successors are decorated, we can decorate this node
        Automaton auto = null;
        if (node instanceof NormalNode) {
            NormalNode normalNode = (NormalNode) node;
            if (successors == null || successors.isEmpty()) {
                // this should be a string leaf node
                AbstractTacPlace place = normalNode.getPlace();
                if (place.isLiteral()) {
                    auto = Automaton.makeString(place.toString());
                } else {
                    // this case should not happen any longer (now that
                    // we have "uninit" nodes, see below)
                    throw new RuntimeException("SNH: " + place + ", " + normalNode.getCfgNode().getFileName() + "," +
                        normalNode.getCfgNode().getOriginalLineNumber());
                }
            } else {
                // this is an interior node, not a leaf node;
                // the automaton for this node is the union of all the
                // successor automatas
                for (AbstractNode succ : successors) {
                    if (succ == node) {
                        // a simple loop, can be ignored
                        continue;
                    }
                    Automaton succAuto = deco.get(succ);
                    if (succAuto == null) {
                        throw new RuntimeException("SNH");
                    }
                    if (auto == null) {
                        auto = succAuto;  // cloning not necessary here
                    } else {
                        auto = auto.union(succAuto);
                    }
                }
            }
        } else if (node instanceof BuiltinFunctionNode) {
            auto = this.makeAutoForOp((BuiltinFunctionNode) node, deco, dependencyGraph);
        } else if (node instanceof CompleteGraphNode) {
            // for SCC nodes, we generate a coarse string approximation (.* automaton);
            // the taint value depends on the taint value of the successors:
            // if any of the successors is tainted in any way, we make the resulting
            // automaton tainted as well

            /*
             * this approach works under the assumption that the SCC contains
             * no "evil" functions (functions that always return a tainted value),
             * and that the direct successors of the SCC are not <uninit> nodes
             * (see there, below);
             * it is primarily based on the fact that SCCs can never contain
             * <uninit> nodes, because <uninit> nodes are always leaf nodes in the dependency
             * graph (since they have no successors);
             * under the above assumptions and observations, it is valid to
             * say that the taint value of an SCC node solely depends on
             * the taint values of its successors, and that is exactly what we
             * do here
             *
             */

            Transition.Taint taint = Transition.Taint.Untainted;
            for (AbstractNode successor : successors) {
                if (successor == node) {
                    // a simple loop, should be part of the SCC
                    throw new RuntimeException("SNH");
                }
                Automaton succAuto = deco.get(successor);
                if (succAuto == null) {
                    throw new RuntimeException("SNH");
                }
                if (succAuto.hasTaintedTransitions()) {
                    taint = Transition.Taint.Directly;
                    break;
                }
            }

            auto = Automaton.makeAnyString(taint);
        } else if (node instanceof UninitializedNode) {
            // retrieve predecessor
            Set<AbstractNode> preds = dependencyGraph.getPredecessors(node);
            if (preds.size() != 1) {
                throw new RuntimeException("SNH");
            }
            AbstractNode pre = preds.iterator().next();

            if (pre instanceof NormalNode) {
                NormalNode preNormal = (NormalNode) pre;
                switch (this.getInitialTaintForPlace(preNormal.getPlace())) {
                    case ALWAYS:
                    case IF_REGISTER_GLOBALS:
                        auto = Automaton.makeAnyString(Transition.Taint.Directly);
                        break;
                    case NEVER:
                        auto = Automaton.makeAnyString(Transition.Taint.Untainted);
                        break;
                    default:
                        throw new RuntimeException("SNH");
                }
            } else if (pre instanceof CompleteGraphNode) {
                // this case can really happen (e.g.: dcpportal: advertiser.php, forums.php);

                // take a look at the "real" predecessors (i.e., take a look "into"
                // the SCC node): if there is exactly one predecessor, namely a
                // NormalNode, and if the contained place is initially untainted,
                // there is no danger from here; else: we will have to set it to tainted
                Set<AbstractNode> origPreds = origDependencyGraph.getPredecessors(node);
                if (origPreds.size() == 1) {
                    AbstractNode origPre = origPreds.iterator().next();
                    if (origPre instanceof NormalNode) {
                        NormalNode origPreNormal = (NormalNode) origPre;

                        switch (this.getInitialTaintForPlace(origPreNormal.getPlace())) {
                            case ALWAYS:
                            case IF_REGISTER_GLOBALS:
                                auto = Automaton.makeAnyString(Transition.Taint.Directly);
                                break;
                            case NEVER:
                                auto = Automaton.makeAnyString(Transition.Taint.Untainted);
                                break;
                            default:
                                throw new RuntimeException("SNH");
                        }
                    } else {
                        auto = Automaton.makeAnyString(Transition.Taint.Directly);
                    }
                } else {
                    // conservative decision for this SCC
                    auto = Automaton.makeAnyString(Transition.Taint.Directly);
                }
            } else {
                throw new RuntimeException("SNH: " + pre.getClass());
            }
        } else {
            throw new RuntimeException("SNH");
        }

        if (auto == null) {
            throw new RuntimeException("SNH");
        }

        deco.put(node, auto);
    }

    /**
     * Returns an automaton for the given operation node.
     *
     * @param node
     * @param deco
     * @param dependencyGraph
     *
     * @return
     */
    private Automaton makeAutoForOp(
        BuiltinFunctionNode node, Map<AbstractNode, Automaton> deco, DependencyGraph dependencyGraph
    ) {
        List<AbstractNode> successors = dependencyGraph.getSuccessors(node);
        if (successors == null) {
            successors = new LinkedList<>();
        }

        Automaton retMe = null;

        String opName = node.getName();

        List<Integer> multiList = new LinkedList<>();

        if (!node.isBuiltin()) {
            // call to function or method for which no definition
            // could be found

            AbstractCfgNode cfgNodeX = node.getCfgNode();
            if (cfgNodeX instanceof CallUnknownFunction) {
                CallUnknownFunction cfgNode = (CallUnknownFunction) cfgNodeX;
                if (cfgNode.isMethod()) {
                    retMe = Automaton.makeAnyString(Transition.Taint.Untainted);
                } else {
                    retMe = Automaton.makeAnyString(Transition.Taint.Directly);
                }
            } else {
                throw new RuntimeException("SNH");
            }
        } else if (opName.equals(".")) {
            // CONCAT
            for (AbstractNode succ : successors) {
                Automaton succAuto = deco.get(succ);
                if (retMe == null) {
                    retMe = succAuto;
                } else {
                    retMe = retMe.concatenate(succAuto);
                }
            }

            // WEAK SANITIZATION FUNCTIONS *******************************
            // ops that perform sanitization, but which are insufficient
            // in cases where the output is not enclosed by quotes in an SQL query
        } else if (isWeakSanitation(opName, multiList)) {
            retMe = Automaton.makeAnyString(Transition.Taint.Indirectly);

            // STRONG SANITIZATION FUNCTIONS *******************************
            // e.g., ops that return numeric values
        } else if (isStrongSanitation(opName)) {
            retMe = Automaton.makeAnyString(Transition.Taint.Untainted);

            // EVIL FUNCTIONS ***************************************
            // take care: if you define evil functions, you must adjust
            // the treatment of SCC nodes in decorate()

            // MULTI-OR-DEPENDENCY **********************************
        } else if (useTransducers && opName.equals("str_replace")) {
            if (successors.size() < 3) {
                throw new RuntimeException("SNH");
            }
            Automaton searchAuto = deco.get(successors.get(0));
            Automaton replaceAuto = deco.get(successors.get(1));
            Automaton subjectAuto = deco.get(successors.get(2));

            // search and replace have to be finite strings;
            // extract them
            boolean supported = true;
            String searchString = null;
            String replaceString = null;
            if (searchAuto.isFinite() && replaceAuto.isFinite()) {
                Set<String> searchSet = searchAuto.getFiniteStrings();
                Set<String> replaceSet = replaceAuto.getFiniteStrings();
                if (searchSet.size() != 1 || replaceSet.size() != 1) {
                    supported = false;
                } else {
                    searchString = searchSet.iterator().next();
                    replaceString = replaceSet.iterator().next();
                }
            } else {
                supported = false;
            }
            if (!supported) {
                throw new RuntimeException("not supported yet");
            }

            Automaton transduced = new MyTransductions().str_replace(searchString, replaceString, subjectAuto);
            return transduced;
        } else if (isMultiDependencyOperation(opName, multiList)) {
            Transition.Taint taint = this.multiDependencyAuto(successors, deco, multiList, false);
            retMe = Automaton.makeAnyString(taint);
        } else if (isInverseMultiDependencyOperation(opName, multiList)) {
            Transition.Taint taint = this.multiDependencyAuto(successors, deco, multiList, true);
            retMe = Automaton.makeAnyString(taint);

            // CATCH-ALL ********************************************
        } else {
            System.out.println("Unmodeled builtin function (SQL): " + opName);

            // conservative decision for operations that have not been
            // modeled yet: .*
            retMe = Automaton.makeAnyString(Transition.Taint.Directly);
        }

        return retMe;
    }

    /**
     * Checks if the given node (inside the given function) is a sensitive sink.
     *
     * Adds an appropriate sink object to the given list if it is a sink.
     *
     * @param cfgNodeX
     * @param traversedFunction
     * @param sinks
     */
    protected void checkForSink(AbstractCfgNode cfgNodeX, TacFunction traversedFunction, List<Sink> sinks) {
        if (cfgNodeX instanceof CallBuiltinFunction) {
            // builtin function sinks
            CallBuiltinFunction cfgNode = (CallBuiltinFunction) cfgNodeX;
            String functionName = cfgNode.getFunctionName();

            checkForSinkHelper(functionName, cfgNode, cfgNode.getParamList(), traversedFunction, sinks);
        } else if (cfgNodeX instanceof CallPreparation) {
            CallPreparation cfgNode = (CallPreparation) cfgNodeX;
            String functionName = cfgNode.getFunctionNamePlace().toString();

            // user-defined custom sinks

            checkForSinkHelper(functionName, cfgNode, cfgNode.getParamList(), traversedFunction, sinks);
        }
    }

    private void checkForSinkHelper(
        String functionName, AbstractCfgNode cfgNode,
        List<TacActualParameter> paramList, TacFunction traversedFunction, List<Sink> sinks
    ) {
        if (this.vulnerabilityAnalysisInformation.getSinks().containsKey(functionName)) {
            Sink sink = new Sink(cfgNode, traversedFunction);
            for (Integer param : this.vulnerabilityAnalysisInformation.getSinks().get(functionName)) {
                if (paramList.size() > param) {
                    sink.addSensitivePlace(paramList.get(param).getPlace());
                    // add this sink to the list of sensitive sinks
                    sinks.add(sink);
                }
            }
        }
    }

    private Transition.Taint multiDependencyAuto(
        List<AbstractNode> successors, Map<AbstractNode, Automaton> deco, List<Integer> indices, boolean inverse
    ) {
        boolean indirectly = false;
        Set<Integer> indexSet = new HashSet<>(indices);

        int count = -1;
        for (AbstractNode successor : successors) {
            count++;

            // check if there is a dependency on this successor
            if (inverse) {
                if (indexSet.contains(count)) {
                    continue;
                }
            } else {
                if (!indexSet.contains(count)) {
                    continue;
                }
            }

            Automaton successorAutomaton = deco.get(successor);
            if (successorAutomaton == null) {
                throw new RuntimeException("SNH");
            }
            if (successorAutomaton.hasDirectlyTaintedTransitions()) {
                return Transition.Taint.Directly;
            }
            if (successorAutomaton.hasIndirectlyTaintedTransitions()) {
                indirectly = true;
            }
        }

        if (indirectly) {
            return Transition.Taint.Indirectly;
        } else {
            // no tainted successors have been found
            return Transition.Taint.Untainted;
        }
    }

    void dumpDotAuto(Automaton auto, String graphName, String path) {
        String filename = graphName + ".dot";
        (new File(path)).mkdir();

        try {
            Writer outWriter = new FileWriter(path + "/" + filename);
            String autoDot = auto.toDot();
            outWriter.write(autoDot);
            outWriter.close();
        } catch (IOException e) {
            System.out.println(e.getMessage());
            return;
        }

        if (MyOptions.option_P) {
            if (auto.isFinite()) {
                Set<String> finiteStringsSet = auto.getFiniteStrings();
                List<String> finiteStrings = new LinkedList<>(finiteStringsSet);
                Collections.sort(finiteStrings);
                System.out.println();
                System.out.println("IS FINITE");
                for (String finite : finiteStrings) {
                    System.out.println();
                    System.out.println("Finite BEGIN");
                    System.out.println(finite);
                    System.out.println("Finite END");
                }
            }

            System.out.println();
            System.out.println("Prefix BEGIN");
            System.out.println(auto.getCommonPrefix());
            System.out.println("Prefix END");
            System.out.println();

            System.out.println("Suffix BEGIN");
            System.out.println(auto.getCommonSuffix());
            System.out.println("Suffix END");
            System.out.println();
        }
    }

    void dumpDotAutoUnique(Automaton auto, String graphName, String path) {
        String filename = graphName + ".dot";
        (new File(path)).mkdir();

        try {
            Writer outWriter = new FileWriter(path + "/" + filename);
            String autoDot = auto.toDotUnique();
            outWriter.write(autoDot);
            outWriter.close();
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }
}