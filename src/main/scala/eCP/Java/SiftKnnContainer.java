package eCP.Java;

import java.io.Serializable;
import java.util.*;

public class SiftKnnContainer implements Serializable {
    public class knnPair implements Comparable<knnPair>, Serializable {
        public int pointID = -1;
        public int distance = Integer.MAX_VALUE;

        @Override
        public int compareTo(knnPair o) {
            if (o.distance > this.distance) {
                return -1;
            } else if (o.distance < this.distance) {
                return 1;
            }
            return 0;
        }
    };

    protected int cID = -1;
    public int getcID() {
        return cID;
    }
    public void setcID(int i) {
        cID = i;
    }

    /*
    protected int knnID = -1;
    public int getknnID() {
        return knnID;
    }
    public void setknnID(int i) {
        knnID = i;
    }
    protected int hasBeenScanned;
     */

    // member variables for the knn container
    protected SiftDescriptorContainer queryPoint;
    protected int k;
    protected int n;
    protected int maxDistance;
    protected int maxIndex;
    protected knnPair[] theKNN;
    protected boolean doScan = true;
    protected int b;

    //private 	int 						dist;

    public SiftKnnContainer() {
        queryPoint = null;
        b = 1;
        //hasBeenScanned = 1;
        setK(10);
    }

    public SiftKnnContainer(int k) {
        queryPoint = null;
        b = 1;
        //hasBeenScanned = 1;
        setK(k);
    }

    public SiftKnnContainer(int k, int b) {
        queryPoint = null;
        this.b = b;
        //hasBeenScanned = b;
        setK(k);
    }

    public void setB(int b) {
        this.b = b;
        //hasBeenScanned = b;
    }

    //@Override
    public void SetQueryPoint(SiftDescriptorContainer queryPoint) {
        this.queryPoint = queryPoint;
    }

    //@Override
    public SiftDescriptorContainer getQueryPoint() {
        return queryPoint;
    }

    //@Override
    public void setK(int k) {
        this.k = k;
        theKNN = new knnPair[k];
        for (int i = 0; i < k; ++i) {
            theKNN[i] = new knnPair();
        }
        maxIndex = 0;
        maxDistance = Integer.MAX_VALUE;
    }

    //@Override
    public int getK(){ return this.k;}

    //@Override
    public int[] getKnnList() {
        int x = k;
        if (n < k) {
            x = n;
        }
        int[] ret = new int[x];
        for (int i = 0; i < x; ++i) {
            ret[i] = theKNN[i].pointID;
        }
        return ret;
    }

    //@Override
    public void sortKnnList() {
        // we want pointID ordered according to distance
        synchronized (this) {
            if (theKNN.length > 1) {
                Arrays.sort(theKNN);
            }
            maxIndex = theKNN.length - 1;
            this.notify();
        }
    }

    //@Override
    public boolean add(SiftDescriptorContainer point, int index) {
        int distance = Integer.MAX_VALUE;
        if (doScan && queryPoint != null && point.vector.length == queryPoint.vector.length) {
            //distance = DistanceWithHalt( point );
            distance = DistanceWithHaltUnrolled(point);
        }
        return add(index, distance);
    }

    //@Override
    public boolean add(SiftDescriptorContainer point) {
        int distance = Integer.MAX_VALUE;
        if (doScan && queryPoint != null && point.vector.length == queryPoint.vector.length) {
            distance = DistanceWithHalt( point );
            //distance = DistanceWithHaltUnrolled(point);
        }
        return add(point.id, distance);
    }

    //@Override
    public boolean addNoDuplicateIDs( int id, int distance) {
        if (distance < maxDistance) {
            // Do we want to prevent double counting the same ID?
            for (int i = 0; i < n; ++i) {
                if (theKNN[i].pointID == id) {
                    if (theKNN[i].distance > distance) {
                        theKNN[i].distance = distance;
                        // did we just overwrite the maxdistance, if so it may not be the max any more.
                        if (i == maxIndex) {
                            maxDistance = distance;
                            for (int x = 0; x < n; ++x) {
                                if (theKNN[x].distance > maxDistance) {
                                    maxIndex = x;
                                    maxDistance = theKNN[x].distance;
                                }
                            }
                        }
                        return true;
                    }
                    return false;
                }
            }
        }
        return add(id, distance);
    }

    public synchronized boolean add(int id, int distance) {

        if (distance < maxDistance) {
            // Add the to the k-NN
            //synchronized (theKNN)
            {
                // n is neighbour counter.. if it is lower then k we have empty space in the k-NN
                if (n < k - 1) {
                    // plenty of space, just put it in the first such spot in the array.
                    theKNN[maxIndex].distance = distance;
                    theKNN[maxIndex].pointID = id;
                    n++;
                    maxIndex++;
                } else if (n < k) {
                    // last empty space
                    theKNN[maxIndex].distance = distance;
                    theKNN[maxIndex].pointID = id;
                    n++;
                    maxDistance = distance;

                    // with knn full, find the farthest point as next to kick-out-candidate
                    for (int i = 0; i < n; ++i) {
                        if (theKNN[i].distance > maxDistance) {
                            maxIndex = i;
                            maxDistance = theKNN[i].distance;
                        }
                    }
                } else {
                    theKNN[maxIndex].distance = distance;
                    theKNN[maxIndex].pointID = id;
                    maxDistance = distance;

                    // with knn full, find the farthest point as next to kick-out-candidate
                    for (int i = 0; i < n; ++i) {
                        if (theKNN[i].distance > maxDistance) {
                            maxIndex = i;
                            maxDistance = theKNN[i].distance;
                        }
                    }
                }
                //theKNN.notify();
            } // end of synchronization
            return true;
        }// end if distance
        return false;
    }

    protected int DistanceWithHalt(SiftDescriptorContainer point) {
        int dist = 0;
        int a;
        for (int i = 0; i < point.vector.length; ++i) {
            //a = (point.vector[i] & 0xFF) - (queryPoint.vector[i] & 0xFF);
            a = point.vector[i] - queryPoint.vector[i];
            dist += a * a;
            if (dist > maxDistance) {
                return Integer.MAX_VALUE;
            }
        }
        return dist;
    }

    protected int DistanceWithHaltUnrolled(SiftDescriptorContainer point) {
/*  Version 1 - Standard unwind by 8 */
        int dist = 0;
        int i = 0;

        for (; i < 128; ) {
            dist += (((int) point.vector[i]) - ((int) queryPoint.vector[i])) *
                    (((int) point.vector[i]) - ((int) queryPoint.vector[i++]));
            dist += (((int) point.vector[i]) - ((int) queryPoint.vector[i])) *
                    (((int) point.vector[i]) - ((int) queryPoint.vector[i++]));
            dist += (((int) point.vector[i]) - ((int) queryPoint.vector[i])) *
                    (((int) point.vector[i]) - ((int) queryPoint.vector[i++]));
            dist += (((int) point.vector[i]) - ((int) queryPoint.vector[i])) *
                    (((int) point.vector[i]) - ((int) queryPoint.vector[i++]));
            dist += (((int) point.vector[i]) - ((int) queryPoint.vector[i])) *
                    (((int) point.vector[i]) - ((int) queryPoint.vector[i++]));
            dist += (((int) point.vector[i]) - ((int) queryPoint.vector[i])) *
                    (((int) point.vector[i]) - ((int) queryPoint.vector[i++]));
            dist += (((int) point.vector[i]) - ((int) queryPoint.vector[i])) *
                    (((int) point.vector[i]) - ((int) queryPoint.vector[i++]));
            dist += (((int) point.vector[i]) - ((int) queryPoint.vector[i])) *
                    (((int) point.vector[i]) - ((int) queryPoint.vector[i++]));
            if (dist > maxDistance) {
                dist = Integer.MAX_VALUE;
                return dist;
            }
        }

//*/
/*  Version 2 * /
		dist = 0;
		int a, b, c, d, e, f, g, h;
		int a2, b2, c2, d2, e2, f2, g2, h2;
		for ( int i=0; i < point.vector.length; ++i ) {
			a = point.vector[i] - queryPoint.vector[i++];
			b = point.vector[i] - queryPoint.vector[i++];
			c = point.vector[i] - queryPoint.vector[i++];
			d = point.vector[i] - queryPoint.vector[i++];
			e = point.vector[i] - queryPoint.vector[i++];
			f = point.vector[i] - queryPoint.vector[i++];
			g = point.vector[i] - queryPoint.vector[i++];
			h = point.vector[i] - queryPoint.vector[i++];
			a2= point.vector[i] - queryPoint.vector[i++];
			b2= point.vector[i] - queryPoint.vector[i++];
			c2= point.vector[i] - queryPoint.vector[i++];
			d2= point.vector[i] - queryPoint.vector[i++];
			e2= point.vector[i] - queryPoint.vector[i++];
			f2= point.vector[i] - queryPoint.vector[i++];
			g2= point.vector[i] - queryPoint.vector[i++];
			h2 = point.vector[i] - queryPoint.vector[i];
			dist = dist + a*a + b*b + c*c + d*d + e*e + f*f + g*g + h*h + a2*a2 + b2*b2 + c2*c2 + d2*d2 + e2*e2 + f2*f2 + g2*g2 + h2*h2;
			dist = dist + a*a + b*b + c*c + d*d;
			if ( dist > maxDistance ) {
				dist =  Integer.MAX_VALUE;
				return dist;
			}
		}
//*/
/* Version 3 	No unwinding * /	
		dist = 0;
		int a;
		for (int i=0; i < point.vector.length; ++i) {
			//a = (point.vector[i] & 0xFF) - (queryPoint.vector[i] & 0xFF);
			a = point.vector[i] - queryPoint.vector[i];
			dist += a * a;
			if ( dist > maxDistance ) {
				dist =  Integer.MAX_VALUE;
				return dist;
			}
		}
//*/
/*  Version 4 - 32 full, + 8 checks every 8 lines + 8 checks every 4 lines * /
		dist = 0;
		int i=0;
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
		for (; i < 96; ) {
			if ( dist > maxDistance ) {
				dist =  Integer.MAX_VALUE;
				return dist;
			}
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
		}
		for (; i < 128; ) {
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			dist += (point.vector[i] - queryPoint.vector[i]) * (point.vector[i] - queryPoint.vector[i++]);
			if ( dist > maxDistance ) {
				dist =  Integer.MAX_VALUE;
				return dist;
			}
		}
		
//*/
        return dist;
    }

    public static int fullDistanceUnrolled( SiftDescriptorContainer a, SiftDescriptorContainer b) {
        if (a.vector.length != b.vector.length) {
            return Integer.MAX_VALUE;
        }
        int dist = 0;
        int i = 0;
        for (; i < a.vector.length; ) {
            dist += (((int) a.vector[i]) - ((int) b.vector[i])) *
                    (((int) a.vector[i]) - ((int) b.vector[i++]));
            dist += (((int) a.vector[i]) - ((int) b.vector[i])) *
                    (((int) a.vector[i]) - ((int) b.vector[i++]));
            dist += (((int) a.vector[i]) - ((int) b.vector[i])) *
                    (((int) a.vector[i]) - ((int) b.vector[i++]));
            dist += (((int) a.vector[i]) - ((int) b.vector[i])) *
                    (((int) a.vector[i]) - ((int) b.vector[i++]));
            dist += (((int) a.vector[i]) - ((int) b.vector[i])) *
                    (((int) a.vector[i]) - ((int) b.vector[i++]));
            dist += (((int) a.vector[i]) - ((int) b.vector[i])) *
                    (((int) a.vector[i]) - ((int) b.vector[i++]));
            dist += (((int) a.vector[i]) - ((int) b.vector[i])) *
                    (((int) a.vector[i]) - ((int) b.vector[i++]));
            dist += (((int) a.vector[i]) - ((int) b.vector[i])) *
                    (((int) a.vector[i]) - ((int) b.vector[i++]));
        }
        return dist;
    }

    //@Override
    public boolean hasBeenScaned() {
        /*if (hasBeenScanned == 0) {
            return true;
        }*/
        return false;
    }

    //@Override
    public synchronized void hasBeenScaned(boolean setValue) {
        /*if (setValue == true) {
            hasBeenScanned--;
        }*/
    }

    //@Override
    public void haltScan() {
        doScan = false;
    }

    //@Override
    public boolean getDoScan() {
        return doScan;
    }

    //@Override
    public int getB() {
        return this.b;
    }

    public knnPair[] getknnPairArray( ) {
        return theKNN;
    }

    public static SiftKnnContainer mergeto1000(SiftKnnContainer a, SiftKnnContainer b) {
       // create a new knn and add the values from the other input values a and b
        SiftKnnContainer ret = new SiftKnnContainer( 1000 );
        knnPair[] knn = a.getknnPairArray();
        for (int i = 0; i < a.getK() ; i++) {
            if (knn[i].distance != Integer.MAX_VALUE) {
                ret.add(knn[i].pointID, knn[i].distance);
            }
        }
        knn = b.getknnPairArray();
        for (int i = 0; i < b.getK() ; i++) {
            if (knn[i].distance != Integer.MAX_VALUE) {
                ret.add(knn[i].pointID, knn[i].distance);
            }
        }
        return ret;
    }
    public static SiftKnnContainer mergetosizeofboth(SiftKnnContainer a, SiftKnnContainer b) {
        // create a new knn and add the values from the other input values a and b
        SiftKnnContainer ret = new SiftKnnContainer( a.getK() + b.getK() );
        ret.SetQueryPoint(a.getQueryPoint());

        knnPair[] knn = a.getknnPairArray();
        for (int i = 0; i < knn.length ; i++) {
            if (knn[i].distance != Integer.MAX_VALUE) {
                ret.addNoDuplicateIDs(knn[i].pointID, knn[i].distance);
            }
        }
        knn = b.getknnPairArray();
        for (int i = 0; i < knn.length ; i++) {
            if (knn[i].distance != Integer.MAX_VALUE) {
                ret.addNoDuplicateIDs(knn[i].pointID, knn[i].distance);
            }
        }
        return ret;
    }

    public static SiftKnnContainer mergetosize( SiftKnnContainer a,
                                                SiftKnnContainer b,
                                                int size,
                                                boolean allowDuplicate,
                                                boolean sortFirst) {
        SiftKnnContainer ret = new SiftKnnContainer( size );
        ret.SetQueryPoint(a.getQueryPoint());

        // sort first, used to take top NN of k-NN (if size smaller than k of k-NNs).
        if (sortFirst) {
            a.sortKnnList();
            b.sortKnnList();
        }

        knnPair[] knna = a.getknnPairArray();
        knnPair[] knnb = b.getknnPairArray();

        // In images search there may be many points with same ID, this will remover duplicates and prevent burst-votes
        if (allowDuplicate) {
            for (int i = 0; i < knna.length ; i++) {
                ret.addNoDuplicateIDs(knna[i].pointID, knna[i].distance);
            }
            for (int i = 0; i < knnb.length ; i++) {
                ret.addNoDuplicateIDs(knnb[i].pointID, knnb[i].distance);
            }
        } else {
            for (int i = 0; i < knna.length ; i++) {
                ret.add(knna[i].pointID, knna[i].distance);
            }
            for (int i = 0; i < knnb.length ; i++) {
                ret.add(knnb[i].pointID, knnb[i].distance);
            }
        }
        if (sortFirst) {
            ret.sortKnnList();
        }
        return ret;
    }

    public static SiftKnnContainer mergetosizeoflarger(SiftKnnContainer a, SiftKnnContainer b) {
        // create a new knn and add the values from the other input values a and b
        int size = a.getK();
        if (b.getK() > size ) {
            size = b.getK();
        }
        SiftKnnContainer ret = new SiftKnnContainer( size );
        ret.SetQueryPoint(a.getQueryPoint());
        knnPair[] knn = a.getknnPairArray();
        for (int i = 0; i < knn.length ; i++) {
            if (knn[i].distance != Integer.MAX_VALUE) {
                ret.addNoDuplicateIDs(knn[i].pointID, knn[i].distance);
            }
        }
        knn = b.getknnPairArray();
        for (int i = 0; i < knn.length ; i++) {
            if (knn[i].distance != Integer.MAX_VALUE) {
                ret.addNoDuplicateIDs(knn[i].pointID, knn[i].distance);
            }
        }
        return ret;
    }

    public static SiftKnnContainer voteAggregate( SiftKnnContainer a, SiftKnnContainer b) {
        // we want to merge votes.
        Map<Integer, Integer> votes = new HashMap<>();
        int qpsum = 0;
        a.sortKnnList();
        if ( a.getDoScan() ) {
            // scan k-NN that has distance values ..
            int[] list = a.getKnnList();
            for (int i=0; i<list.length; ++i) {
                if ( votes.containsKey( list[i] )  ) {
                    int val = votes.get(list[i]).intValue();
                    votes.put( list[i], val+1);
                } else {
                    votes.put( list[i], 1 );
                }
            }
            qpsum += 1;
        } else {
            // a is an already a vote-aggregate k-NN so distance part contains aggregated votes
            knnPair[] listpairs = a.getknnPairArray();
            // since votes is empty we simply put all the values into the map
            for (int i=0; i< listpairs.length; ++i ) {
                votes.put( listpairs[i].pointID, listpairs[i].distance );
            }
            qpsum += a.getcID();
        }

        if ( b.getDoScan() ) {
            // by now we have put stuff inn the map
            int[] list = b.getKnnList();
            for (int i = 0; i < list.length; ++i) {
                if (votes.containsKey(list[i])) {
                    // increment if already in the list
                    int val = votes.get(list[i]).intValue();
                    votes.put( list[i], val+1);
                }else {
                    votes.put(list[i], 1);
                }
            }
            qpsum += 1;
        } else {
            // b is a list so the distances are vote-counts
            knnPair[] listpairs = b.getknnPairArray();
            for (int i=0; i<listpairs.length; ++i ) {
                if (!votes.containsKey(listpairs[i].pointID)) {
                    // set the current count as no counts existed before
                    votes.put( listpairs[i].pointID, listpairs[i].distance );
                } else {
                    // some votes aggregates exist in the map so we sum.
                    int val = votes.get(listpairs[i].pointID).intValue();
                    votes.put( listpairs[i].pointID, listpairs[i].distance + val );
                }
            }
            qpsum += b.getcID();
        }
        // get the keys
        Set<Integer> keys = votes.keySet();

        //* filter the number of results, throw away 1-votes if too many
        if ( keys.size() > 3000 ) {
            Set<Integer> keep = new HashSet<Integer>(0);
            int maxValue = 1;
            for (Integer key : keys) {
                int vc = votes.get(key).intValue();
                if ( vc > 1 ) {
                    keep.add(key);
                }
            }
            if (keep.size() > 30) {
                keys = keep;
            }
        } // */
        // make a new container for the results
        SiftKnnContainer ret = new SiftKnnContainer( keys.size() );
        ret.haltScan();
        ret.setcID(qpsum);
        for( Integer key : keys) {
            ret.add(key.intValue(), votes.get(key).intValue());
        }
        ret.sortKnnList();
        return ret;
    }

    public static SiftKnnContainer shortlist ( SiftKnnContainer inn, int maxSize, boolean ascending ) {
        // shrink the size of a k-NN
        SiftKnnContainer ret = inn;
        if ( inn.getK() > maxSize ) {
            ret = new SiftKnnContainer(maxSize);
            ret.setcID(inn.getcID());
            ret.haltScan();
            // this will sort the list ascending, with the lowest values first
            inn.sortKnnList();
            knnPair[] listpair = inn.getknnPairArray();
            if (ascending) {
                for (int i = 0; i < maxSize; ++i) {
                    ret.add(listpair[i].pointID, listpair[i].distance);
                }
            }else {
                // we need to get the maxSize last values from the list instead of the first values
                for (int i = 0; i < maxSize; ++i) {
                    ret.add(listpair[listpair.length-(1+i)].pointID, listpair[listpair.length-(1+i)].distance);
                }
            }
        }
        return ret;
    }

    //@Override
    public boolean contains(int pointID) {
        for (int i = 0; i < theKNN.length; ++i) {
            if (theKNN[i].pointID == pointID) {
                return true;
            }
        }
        return false;
    }

    //@Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append(this.cID + "\n\n");
        for (int i = 0; i < theKNN.length; ++i) {
            if (theKNN[i].distance != Integer.MAX_VALUE) {
                sb.append(i);
                sb.append(" ");
                sb.append(theKNN[i].pointID);
                sb.append(" ");
                sb.append(theKNN[i].distance);
                sb.append("\n");
            }
        }
        return sb.toString();
    }
    public String toStringWithSscore() {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < theKNN.length; ++i) {
            if (theKNN[i].distance != Integer.MAX_VALUE) {
                sb.append(i);
                sb.append(" ");
                sb.append(theKNN[i].pointID);
                sb.append(" ");
                sb.append(theKNN[i].distance);
                sb.append(" ");
                double  sscore      = ( theKNN[0].distance / (2.0 * theKNN[i].distance) );
                sb.append( 0.5 - sscore );
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}