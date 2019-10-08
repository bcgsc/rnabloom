/* 
 * Copyright (C) 2018 BC Cancer Genome Sciences Centre
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package rnabloom.olc;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.jgrapht.Graphs;
import org.jgrapht.alg.TransitiveReduction;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import rnabloom.io.FastaReader;
import rnabloom.io.FastaWriter;
import rnabloom.io.PafReader;
import rnabloom.io.PafRecord;
import static rnabloom.util.SeqUtils.bytesToString;
import static rnabloom.util.SeqUtils.reverseComplement;
import static rnabloom.util.SeqUtils.stringToBytes;


/**
 *
 * @author kmnip
 */
public class Layout {
    
    private DefaultDirectedGraph<String, OverlapEdge> graph;
    private String overlapPafPath;
    private String seqFastaPath;
    private boolean stranded;
    private int maxEdgeClip = 100;
    private float minAlnId = 0.50f;
    private int minOverlapMatches = 200;
    
    public Layout(String seqFile, String pafFile, boolean stranded, int maxEdgeClip, float minAlnId, int minOverlapMatches) {
        this.graph = new DefaultDirectedGraph<>(OverlapEdge.class);
        this.overlapPafPath = pafFile;
        this.seqFastaPath = seqFile;
        this.stranded = stranded;
        this.maxEdgeClip = maxEdgeClip;
        this.minAlnId = minAlnId;
        this.minOverlapMatches = minOverlapMatches;
    }
    
    private class OverlapEdge extends DefaultEdge {
        public int sourceStart, sourceEnd, sinkStart, sinkEnd;
        
        public OverlapEdge(int sourceStart, int sourceEnd, int sinkStart, int sinkEnd) {
            this.sourceStart = sourceStart;
            this.sourceEnd = sourceEnd;
            this.sinkStart = sinkStart;
            this.sinkEnd = sinkEnd;
        }
    }
        
    private boolean isContainmentPafRecord(PafRecord record) {
        return record.numMatch / (float)(record.qEnd - record.qStart) >= minAlnId &&
            record.numMatch / (float)(record.tEnd - record.tStart) >= minAlnId &&
            ((record.qStart <= maxEdgeClip && record.qLen - record.qEnd <= maxEdgeClip) ||
            (record.tStart <= maxEdgeClip && record.tLen - record.tEnd <= maxEdgeClip));
    }
    
    private boolean isStrandedContainmentPafRecord(PafRecord record) {
        return !record.reverseComplemented && isContainmentPafRecord(record);
    }
    
    private boolean isDovetailPafRecord(PafRecord r) {
        if (r.numMatch >= minOverlapMatches &&
            r.numMatch / (float)(r.qEnd - r.qStart) >= minAlnId &&
            r.numMatch / (float)(r.tEnd - r.tStart) >= minAlnId) {

            if (r.reverseComplemented) {
                return (r.qEnd >= r.qLen - maxEdgeClip && r.tEnd >= r.tLen - maxEdgeClip && r.qStart > r.tLen - r.tEnd) ||
                        (r.tStart <= maxEdgeClip && r.qStart <= maxEdgeClip && r.qLen - r.qStart > r.tStart);
            }
            else {
                return (r.qEnd >= r.qLen - maxEdgeClip && r.tStart <= maxEdgeClip && r.qStart > r.tStart) ||
                        (r.tEnd >= r.tLen - maxEdgeClip && r.qStart <= maxEdgeClip && r.tStart > r.qStart);
            }

        }
        
        return false;
    }
    
    private boolean isStrandedDovetailPafRecord(PafRecord record) {
        if (!record.reverseComplemented) {
            if (record.numMatch >= minOverlapMatches &&
                record.numMatch / (float)(record.qEnd - record.qStart) >= minAlnId &&
                record.numMatch / (float)(record.tEnd - record.tStart) >= minAlnId &&
                ((record.qEnd >= record.qLen - maxEdgeClip && record.tStart <= maxEdgeClip) ||
                    (record.tEnd >= record.tLen - maxEdgeClip && record.qStart <= maxEdgeClip))) {
                return true;
            }
        }
        
        return false;
    }
    
    private static String getVertexName(String vid) {
        return vid.substring(0, vid.length()-1);
    }
    
    private static char geVertexSign(String vid) {
        return vid.charAt(vid.length()-1);
    }
    
    private static boolean isVertexSignReverseComplement(String vid) {
        return geVertexSign(vid) == '-';
    }
    
    private static char getReverseComplementSign(char sign) {
        switch (sign) {
            case '+':
                return '-';
            case '-':
                return '+';
        }
        
        return '0';
    }
    
    private static String getReverseComplementID(String vid) {
        int lastIndex = vid.length()-1;
        return vid.substring(0, lastIndex) + getReverseComplementSign(vid.charAt(lastIndex));
    }
    
    private OverlapEdge getReverseComplementEdge(OverlapEdge e) {
        String source = graph.getEdgeSource(e);
        String sink = graph.getEdgeTarget(e);
        return graph.getEdge(getReverseComplementID(sink), getReverseComplementID(source));
    }
    
    private void resolveJunctions(HashSet<String> names, boolean strandSpecific) {
        for (String name : names) {
            name += '+';
            
            if (graph.outDegreeOf(name) > 1) {
                Set<OverlapEdge> edges = graph.outgoingEdgesOf(name);
                ArrayDeque<OverlapEdge> edgesToRemove = new ArrayDeque<>(edges.size()-1);
                
                // find the best edge
                Iterator<OverlapEdge> itr = edges.iterator();
                OverlapEdge bestEdge = itr.next();
                int bestOverlap = bestEdge.sourceEnd - bestEdge.sourceStart;
                while (itr.hasNext()) {
                    OverlapEdge e = itr.next();
                    int overlap = e.sourceEnd - e.sourceStart;
                    if (overlap > bestOverlap) {
                        edgesToRemove.add(bestEdge);
                        if (!strandSpecific) {
                            OverlapEdge re = getReverseComplementEdge(bestEdge);
                            if (re != null) {
                                edgesToRemove.add(re);
                            }
                        }
                        
                        bestOverlap = overlap;
                        bestEdge = e;
                    }
                    else {
                        edgesToRemove.add(e);
                        if (!strandSpecific) {
                            OverlapEdge re = getReverseComplementEdge(e);
                            if (re != null) {
                                edgesToRemove.add(re);
                            }
                        }
                    }
                }
                                
                // remove other edges from graph
                graph.removeAllEdges(edgesToRemove);
            }
            
            if (graph.inDegreeOf(name) > 1) {
                Set<OverlapEdge> edges = graph.incomingEdgesOf(name);
                ArrayDeque<OverlapEdge> edgesToRemove = new ArrayDeque<>(edges.size()-1);
                
                // find the best edge
                Iterator<OverlapEdge> itr = edges.iterator();
                OverlapEdge bestEdge = itr.next();
                int bestOverlap = bestEdge.sinkEnd - bestEdge.sinkStart;
                while (itr.hasNext()) {
                    OverlapEdge e = itr.next();
                    int overlap = e.sinkEnd - e.sinkStart;
                    if (overlap > bestOverlap) {
                        edgesToRemove.add(bestEdge);
                        if (!strandSpecific) {
                            OverlapEdge re = getReverseComplementEdge(bestEdge);
                            if (re != null) {
                                edgesToRemove.add(re);
                            }
                        }
                        
                        bestOverlap = overlap;
                        bestEdge = e;
                    }
                    else {
                        edgesToRemove.add(e);
                        if (!strandSpecific) {
                            OverlapEdge re = getReverseComplementEdge(e);
                            if (re != null) {
                                edgesToRemove.add(re);
                            }
                        }
                    }
                }
                
                // remove other edges from graph
                graph.removeAllEdges(edgesToRemove);
            }
        }
    }
    
    private ArrayDeque<String> getUnambiguousRightExtension(String id) {
        ArrayDeque<String> path = new ArrayDeque<>();
        HashSet<String> visited = new HashSet<>();
        visited.add(id);
        
        String n = id;
        while (true) {
            List<String> successors = Graphs.successorListOf(graph, n);
            
            if (successors.size() != 1) {
                break;
            }
            else {
                n = successors.get(0);
                List<String> predecessors = Graphs.predecessorListOf(graph, n);
                if (predecessors.size() > 1 || visited.contains(n)) {
                    break;
                }
                else {
                    path.add(n);
                    visited.add(n);
                }
            }
        }
        
        return path;
    }
    
    private ArrayDeque<String> getUnambiguousLeftExtension(String id) {
        ArrayDeque<String> path = new ArrayDeque<>();
        HashSet<String> visited = new HashSet<>();
        visited.add(id);
        
        String n = id;
        while (true) {
            List<String> predecessor = Graphs.predecessorListOf(graph, n);
            
            if (predecessor.size() != 1) {
                break;
            }
            else {
                n = predecessor.get(0);
                List<String> successors = Graphs.successorListOf(graph, n);
                if (successors.size() > 1 || visited.contains(n)) {
                    break;
                }
                else {
                    path.addFirst(n);
                    visited.add(n);
                }
            }
        }
        
        return path;
    }
    
    public void writeBackboneSequences(String outFastaPath) throws IOException {
        if (stranded) {
            layoutStrandedBackbones(outFastaPath);
        }
        else {
            layoutBackbones(outFastaPath);
        }
    }
    
    private String assemblePath(ArrayDeque<String> path, HashMap<String, byte[]> sequences) {
        StringBuilder sb = new StringBuilder();
        
        Iterator<String> itr = path.iterator();
        String vid = itr.next();
        
        boolean reverseComplement = isVertexSignReverseComplement(vid);
        byte[] bytes = sequences.get(getVertexName(vid));
        int start = 0;
        int end = bytes.length;
        
        while (itr.hasNext()) {
            String vid2 = itr.next();
            
            OverlapEdge edge = graph.getEdge(vid, vid2);
            if (reverseComplement) {
                sb.append(reverseComplement(bytes, edge.sourceEnd, end));
            }
            else {
                sb.append(bytesToString(bytes, start, edge.sourceStart));
            }
            
            vid = vid2;
            bytes = sequences.get(getVertexName(vid));
            reverseComplement = isVertexSignReverseComplement(vid);
            
            if (reverseComplement) {
                end = edge.sinkEnd;
                start = 0;
            }
            else {
                start = edge.sinkStart;
                end = bytes.length;
            }
        }
        
        if (reverseComplement) {
            sb.append(reverseComplement(bytes, start, end));
        }
        else {
            sb.append(bytesToString(bytes, start, end));
        }
        
        return sb.toString();
    }
    
    private void layoutBackbones(String outFastaPath) throws IOException {
        HashMap<String, Integer> lengths = new HashMap<>(); // read id -> length
        HashMap<String, String> longestAlts = new HashMap<>(); // read id -> longest read id
        ArrayDeque<PafRecord> dovetailRecords = new ArrayDeque<>();
               
        // look for containment and dovetails
        PafReader reader = new PafReader(overlapPafPath);
        while (reader.hasNext()) {
            PafRecord r = reader.next();
            
            lengths.put(r.qName, r.qLen);
            lengths.put(r.tName, r.tLen);
            
            if (isContainmentPafRecord(r)) {
                String shorter, longer;
                int longerLen;

                if (r.qLen > r.tLen || (r.qLen == r.tLen && r.qName.compareTo(r.tName) > 0)) {
                    shorter = r.tName;
                    longer = r.qName;
                    longerLen = r.qLen;
                }
                else {
                    shorter = r.qName;
                    longer = r.tName;
                    longerLen = r.tLen;
                }

                if (longestAlts.containsKey(shorter)) {
                    String alt = longestAlts.get(shorter);
                    int altLen = lengths.get(alt);
                    if (altLen < longerLen || (altLen == longerLen && longer.compareTo(alt) > 0)) {
                        longestAlts.put(shorter, longer);
                    }
                }
                else {
                    longestAlts.put(shorter, longer);
                }
            }
            else if (!longestAlts.containsKey(r.qName) &&
                    !longestAlts.containsKey(r.tName) &&
                    isDovetailPafRecord(r)) {
                dovetailRecords.add(r);
            }
        }
        reader.close();
        
        System.out.println("Overlapped sequences: " + NumberFormat.getInstance().format(lengths.size()));
        
        // look for longest reads
        HashSet<String> longestSet = new HashSet<>(longestAlts.values());
        for (String name : lengths.keySet()) {
            if (!longestAlts.containsKey(name) && !longestSet.contains(name)) {
                longestSet.add(name);
            }
        }
        
        System.out.println("          - unique:   " + NumberFormat.getInstance().format(longestSet.size()));
        System.out.println("          - dovetail: " + NumberFormat.getInstance().format(dovetailRecords.size()));
        
        // add nodes for reads
        for (String name : longestSet) {
            graph.addVertex(name + "+");
            graph.addVertex(name + "-");
        }        
        
        // add edges for dovetails
        for (PafRecord r : dovetailRecords) {
            if (longestSet.contains(r.qName) && longestSet.contains(r.tName)) {
                if (r.reverseComplemented) {
                    if (r.qEnd >= r.qLen - maxEdgeClip && r.tEnd >= r.tLen - maxEdgeClip) {
                        graph.addEdge(r.tName+"+", r.qName+"-", new OverlapEdge(r.tStart, r.tEnd, r.qStart, r.qEnd));
                        graph.addEdge(r.qName+"+", r.tName+"-", new OverlapEdge(r.qStart, r.qEnd, r.tStart, r.tEnd));
                    }
                    else if (r.tStart <= maxEdgeClip && r.qStart <= maxEdgeClip) {
                        graph.addEdge(r.qName+"-", r.tName+"+", new OverlapEdge(r.qStart, r.qEnd, r.tStart, r.tEnd));
                        graph.addEdge(r.tName+"-", r.qName+"+", new OverlapEdge(r.tStart, r.tEnd, r.qStart, r.qEnd));
                    }
                }
                else {
                    if (r.qEnd >= r.qLen - maxEdgeClip && r.tStart <= maxEdgeClip) {
                        graph.addEdge(r.qName+"+", r.tName+"+", new OverlapEdge(r.qStart, r.qEnd, r.tStart, r.tEnd));
                        graph.addEdge(r.tName+"-", r.qName+"-", new OverlapEdge(r.tStart, r.tEnd, r.qStart, r.qEnd));
                    }
                    else if (r.tEnd >= r.tLen - maxEdgeClip && r.qStart <= maxEdgeClip) {
                        graph.addEdge(r.tName+"+", r.qName+"+", new OverlapEdge(r.tStart, r.tEnd, r.qStart, r.qEnd));
                        graph.addEdge(r.qName+"-", r.tName+"-", new OverlapEdge(r.qStart, r.qEnd, r.tStart, r.tEnd));
                    }
                }
            }
        }
        
        TransitiveReduction.INSTANCE.reduce(graph);
        resolveJunctions(longestSet, false);
        
        // extract longest read sequences
        HashMap<String, byte[]> longestReadSeqs = new HashMap<>(longestSet.size());
        FastaReader fr = new FastaReader(seqFastaPath);
        FastaWriter fw = new FastaWriter(outFastaPath, false);
        int seqID = 0;
        while (fr.hasNext()) {
            String[] nameSeq = fr.nextWithName();
            String name = nameSeq[0];
            if (longestSet.contains(name)) {
                longestReadSeqs.put(name, stringToBytes(nameSeq[1], lengths.get(name)));
            }
            else if (!lengths.containsKey(name)) {
                // an orphan sequence with no overlaps with other sequences
                fw.write(Integer.toString(++seqID), nameSeq[1]);
            }
        }
        fr.close();
        
        // layout unambiguous paths
        HashSet<String> visitedReadNames = new HashSet<>();
        for (String n : longestSet) {
            if (!visitedReadNames.contains(n)) {
                n += "+";
                
                ArrayDeque<String> path = getUnambiguousLeftExtension(n);
                path.add(n);
                if (!graph.containsEdge(n, path.getFirst())) {
                    // detect cycles
                    path.addAll(getUnambiguousRightExtension(n));
                }
                
                String backbone = assemblePath(path, longestReadSeqs);
                
                // write backbone to FASTA
                String header = Integer.toString(++seqID);
                if (path.size() > 1) {
                    //print(path, ',')
                    header += " path=[" + String.join(",", path) + "]";
                }
                fw.write(header, backbone);
                
                for (String vid : path) {
                    visitedReadNames.add(getVertexName(vid));
                }
            }
        }
        fw.close();
        
        if (seqID > 1)
            System.out.println(NumberFormat.getInstance().format(seqID) + " sequences remaining after layout");
        else
            System.out.println(Integer.toString(seqID) + " sequence remaining after layout");
    }
    
    private void layoutStrandedBackbones(String outFastaPath) throws IOException {
        HashMap<String, Integer> lengths = new HashMap<>(); // read id -> length
        HashMap<String, String> longestAlts = new HashMap<>(); // read id -> longest read id
        ArrayDeque<PafRecord> dovetailRecords = new ArrayDeque<>();
        
        // look for containment and overlaps
        PafReader reader = new PafReader(overlapPafPath);
        while (reader.hasNext()) {
            PafRecord r = reader.next();
            
            lengths.put(r.qName, r.qLen);
            lengths.put(r.tName, r.tLen);
            
            if (isStrandedContainmentPafRecord(r)) {
                String shorter, longer;
                int longerLen;
               
                if (r.qLen > r.tLen || (r.qLen == r.tLen && r.qName.compareTo(r.tName) > 0)) {
                    shorter = r.tName;
                    longer = r.qName;
                    longerLen = r.qLen;
                } 
                else {
                    shorter = r.qName;
                    longer = r.tName;
                    longerLen = r.tLen;
                }

                if (longestAlts.containsKey(shorter)) {
                    String alt = longestAlts.get(shorter);
                    int altLen = lengths.get(alt);
                    if (altLen < longerLen || (altLen == longerLen && longer.compareTo(alt) > 0)) {
                        longestAlts.put(shorter, longer);
                    }
                }
                else {
                    longestAlts.put(shorter, longer);
                }
            }
            else if (!longestAlts.containsKey(r.qName) &&
                    !longestAlts.containsKey(r.tName) &&
                    isStrandedDovetailPafRecord(r)) {
                dovetailRecords.add(r);
            }
        }
        reader.close();
        
        System.out.println("Overlapped sequences: " + NumberFormat.getInstance().format(lengths.size()));
        
        // look for longest reads
        HashSet<String> longestSet = new HashSet<>(longestAlts.values());
        for (String name : lengths.keySet()) {
            if (!longestAlts.containsKey(name) && !longestSet.contains(name)) {
                longestSet.add(name);
            }
        }
        
        System.out.println("          - unique:   " + NumberFormat.getInstance().format(longestSet.size()));
        System.out.println("          - dovetail: " + NumberFormat.getInstance().format(dovetailRecords.size()));
        
        // add nodes for reads
        for (String name : longestSet) {
            graph.addVertex(name + "+");
        }        
        
        // add edges for dovetails
        for (PafRecord r : dovetailRecords) {
            if (!r.reverseComplemented) {
                if (longestSet.contains(r.qName) && longestSet.contains(r.tName)) {
                    if (r.qEnd >= r.qLen - maxEdgeClip && r.tStart <= maxEdgeClip) {
                        graph.addEdge(r.qName+"+", r.tName+"+", new OverlapEdge(r.qStart, r.qEnd, r.tStart, r.tEnd));
                    }
                    else if (r.tEnd >= r.tLen - maxEdgeClip && r.qStart <= maxEdgeClip) {
                        graph.addEdge(r.tName+"+", r.qName+"+", new OverlapEdge(r.tStart, r.tEnd, r.qStart, r.qEnd));
                    }
                }
            }
        }
        
        TransitiveReduction.INSTANCE.reduce(graph);
        resolveJunctions(longestSet, true);
        
        // extract longest read sequences
        HashMap<String, byte[]> longestReadSeqs = new HashMap<>(longestSet.size());
        FastaReader fr = new FastaReader(seqFastaPath);
        FastaWriter fw = new FastaWriter(outFastaPath, false);
        int seqID = 0;
        while (fr.hasNext()) {
            String[] nameSeq = fr.nextWithName();
            String name = nameSeq[0];
            if (longestSet.contains(name)) {
                String seq = nameSeq[1];
                longestReadSeqs.put(name, stringToBytes(seq, seq.length()));
            }
            else if (!lengths.containsKey(name)) {
                // an orphan sequence with no overlaps with other sequences
                fw.write(Integer.toString(++seqID), nameSeq[1]);
            }
        }
        fr.close();
        
        // layout unambiguous paths
        HashSet<String> visitedReadNames = new HashSet<>();
        for (String n : longestSet) {
            if (!visitedReadNames.contains(n)) {
                n += "+";
                
                ArrayDeque<String> path = getUnambiguousLeftExtension(n);
                path.add(n);
                if (!graph.containsEdge(n, path.getFirst())) {
                    // detect cycles
                    path.addAll(getUnambiguousRightExtension(n));
                }
                
                //print(path, ',')
                String backbone = assemblePath(path, longestReadSeqs);
                
                // write backbone to FASTA
                String header = Integer.toString(++seqID);
                if (path.size() > 1) {
                    //print(path, ',')
                    header += " path=[" + String.join(",", path) + "]";
                }
                fw.write(header, backbone);
                
                for (String vid : path) {
                    visitedReadNames.add(getVertexName(vid));
                }
            }
        }
        fw.close();
        
        if (seqID > 1)
            System.out.println(NumberFormat.getInstance().format(seqID) + " sequences remaining after layout");
        else
            System.out.println(Integer.toString(seqID) + " sequence remaining after layout");
    }
    
    public static void main(String[] args) {
        boolean stranded = false;
        String seqFastaPath = "";
        String overlapPafPath = "";
        String backboneFastaPath = "";
        
        try {
            Layout myLayout = new Layout(seqFastaPath, overlapPafPath, stranded, 3, 0.99f, 100);
            myLayout.writeBackboneSequences(backboneFastaPath);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}