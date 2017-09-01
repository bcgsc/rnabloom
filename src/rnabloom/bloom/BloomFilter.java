/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package rnabloom.bloom;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import rnabloom.bloom.buffer.LargeBitBuffer;
import rnabloom.bloom.buffer.UnsafeBitBuffer;
import rnabloom.bloom.buffer.AbstractLargeBitBuffer;
import static java.lang.Math.pow;
import static java.lang.Math.exp;
import static java.lang.Math.log;
import rnabloom.bloom.buffer.BufferComparator;
import rnabloom.bloom.hash.HashFunction2;

/**
 *
 * @author kmnip
 */
public class BloomFilter implements BloomFilterInterface {    
    protected AbstractLargeBitBuffer bitArray;
    protected int numHash;
    protected long size;
    protected HashFunction2 hashFunction;
    protected long popcount = -1;
        
    public BloomFilter(long size, int numHash, HashFunction2 hashFunction) {
        
        this.size = size;
        try {
            //System.out.println("unsafe");
            this.bitArray = new UnsafeBitBuffer(size);
        }
        catch(NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
            this.bitArray = new LargeBitBuffer(size);
        }
        this.numHash = numHash;
        this.hashFunction = hashFunction;
    }
    
    private static final String LABEL_SEPARATOR = ":";
    private static final String LABEL_SIZE = "size";
    private static final String LABEL_NUM_HASH = "numhash";
    private static final String LABEL_FPR = "fpr";
    
    public BloomFilter(File desc, File bits, HashFunction2 hashFunction) throws FileNotFoundException, IOException {
        
        BufferedReader br = new BufferedReader(new FileReader(desc));
        String line;
        while ((line = br.readLine()) != null) {
            String[] entry = line.split(LABEL_SEPARATOR);
            String key = entry[0];
            String val = entry[1];
            switch(key) {
                case LABEL_SIZE:
                    size = Long.parseLong(val);
                    break;
                case LABEL_NUM_HASH:
                    numHash = Integer.parseInt(val);
                    break;
            }
        }
        br.close();
        
        this.hashFunction = hashFunction;
        
        try {
            //System.out.println("unsafe");
            this.bitArray = new UnsafeBitBuffer(size);
        }
        catch(NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
            this.bitArray = new LargeBitBuffer(size);
        }
        
        FileInputStream fin = new FileInputStream(bits);
        this.bitArray.read(fin);
        fin.close();
        
        /**@TODO Assert file size*/
    }
    
    protected long getIndex(long hashVal) {
        // shift right to remove sign bit and modulus the size of buffer
        return (hashVal >>> 1) % size;
    }
    
    public void save(File desc, File bits) throws IOException {
        FileWriter writer = new FileWriter(desc, false);
        
        writer.write(LABEL_SIZE + LABEL_SEPARATOR + this.size + "\n" +
                    LABEL_NUM_HASH + LABEL_SEPARATOR + this.numHash + "\n" +
                    LABEL_FPR + LABEL_SEPARATOR + this.getFPR() + "\n");
        writer.close();
        
        FileOutputStream out = new FileOutputStream(bits, false);
        this.bitArray.write(out);
        out.close();
    }
        
    @Override
    public void add(String key) {
        final long[] hashVals = new long[numHash];
        hashFunction.getHashValues(key, numHash, hashVals);
        add(hashVals);
    }
    
    public void add(final long[] hashVals){
        for (int h=0; h<numHash; ++h) {
            bitArray.set(getIndex(hashVals[h]));
        }
    }
    
    public void add(final long hashVal) {
        add(hashFunction.getHashValues(hashVal, numHash));
    }
    
    public boolean lookupThenAdd(final long[] hashVals) {
        boolean found = true;
        
        for (int h=0; h<numHash; ++h) {
            found = bitArray.getAndSet(h) && found;
        }
        
        return found;
    }
    
    public void addCAS(final long[] hashVals) {
        for (int h=0; h<numHash; ++h) {
            bitArray.setCAS(getIndex(hashVals[h]));
        }        
    }

    @Override
    public boolean lookup(String key) {
        final long[] hashVals = new long[numHash];
        hashFunction.getHashValues(key, numHash, hashVals);
        return lookup(hashVals);
    }

    public boolean lookup(final long[] hashVals) {
        for (int h=0; h<numHash; ++h) {
            if (!bitArray.get(getIndex(hashVals[h]))) {
                return false;
            }
        }
        
        return true;
    }
    
    public boolean lookup(final long hashVal) {
        return lookup(hashFunction.getHashValues(hashVal, numHash));
    }
    
    @Override
    public float getFPR() {
        /* (1 - e(-kn/m))^k
        k = num hash
        m = size
        n = pop count
        */
        
        popcount = bitArray.popCount();
        
//        return (float) pow(1 - exp((float)(-numHash * popcount) / size), numHash);
        return (float) pow((double)(popcount) / (double)(size), numHash);
    }
    
    public long updatePopcount() {
        popcount = bitArray.popCount();
        return popcount;
    }
    
    public long getSizeNeeded(float fpr) {
        if (popcount < 0) {
            popcount = updatePopcount();
        }
        
        return (long) Math.ceil(- popcount *log(fpr) / pow(log(2), 2));
    }
    
    public int getNumHash() {
        return numHash;
    }
    
    public float getOptimalNumHash() {
        if (popcount < 0) {
            popcount = updatePopcount();
        }
        
        return (float) (size / (float) popcount * log(2));
    }
    
    public void empty() {
        this.bitArray.empty();
    }
    
    public void destroy() {
        this.bitArray.destroy();
    }
    
    public boolean equivalent(BloomFilter bf) {
        return this.size == bf.size && 
                this.numHash == bf.numHash &&
                BufferComparator.equivalentBitBuffers(bitArray, bf.bitArray);
    }
}
