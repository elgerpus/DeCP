package com.ru.is.decp;

import java.io.Externalizable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;


public class eCPALTree implements Serializable {
	private class InternalNode implements Serializable {
        private static final long serialVersionUID = 1L;
        public 	int 		                    leaderID 	= -1;
        public 	InternalNode[] 					children 	= null;
		public 	int[]				            leafs		= null;
	}
	private class DynamicInternalNode {
		public	ArrayList<DynamicInternalNode>	children 	= null;
		public 	int 		                    leaderID	= -1;
	}

    private static final long serialVersionUID = 1L;
	private InternalNode				root			= null;
	public  int 						L;
	public  int 						treeA;
	private SiftDescriptorContainer[]	leafs;
    public  boolean                     verboos         = false;
	
	public int getNumberOfLeafClusters() {return leafs.length;}

    public void buildIndexTreeFromLeafs( int L, int treeA, SiftDescriptorContainer[] leafs ) {
		if (L < 1 || L > 10) {
			System.out.println(L + " is not a valid index depth. (eCPALtree.java)");
			System.exit(2);
		}
		System.out.println("Building index tree with L="+L+" and treeA="+treeA );
		this.L = L;
		this.treeA = treeA;
		this.leafs = leafs;
		if ( L == 1 ) { 
			return;
		}
		// build a Dynamic root node 
		int bucketSize = (int) Math.ceil(Math.pow( ( leafs.length ), (1.0 / L ) ));
		System.out.println( "BucketSize="+bucketSize );
		DynamicInternalNode dyRoot = new DynamicInternalNode();
		dyRoot.children = new ArrayList<DynamicInternalNode>(bucketSize);
		dyRoot.leaderID = -1;
		for (int i=0; i<bucketSize; ++i) {
			DynamicInternalNode c = new DynamicInternalNode();
			c.leaderID = i;
			dyRoot.children.add( c );
		}

		// We now have a root node with all the leaders of the top level that can be used for scanning.
		for ( int i=2; i<=L; ++i) {
			int indx = 0;
			int thrs = (int) Math.pow((double)bucketSize, (double)i);
			if (thrs > leafs.length) {
				thrs = leafs.length;
			}
			for(; indx<thrs; ) {
				 // search with limit i 
				DynamicInternalNode[] ret = limitedDyRootSearch(leafs[indx], i-1, dyRoot);
                if (ret == null) {continue;}
                // add top treeA nearest
				DynamicInternalNode dn = new DynamicInternalNode();
				dn.leaderID = leafs[indx].id;
				for (int x=0; x<ret.length; ++x) {
                    if (ret[x] != null ) {
                        if (ret[x].children == null) {
                            ret[x].children = new ArrayList<DynamicInternalNode>(bucketSize);
                        }
                        ret[x].children.add(dn);
                    }
				}
				indx++;
			} // end for index<thrs
		}// end for each level 
		// we have built the tree now but it's still in a dynamic structure
		// make the structure static and fixed size
		root = buildStaticTree(dyRoot, 0);
		// un-reference the dynamic root and call garbage collection to clean up
		dyRoot = null;
		System.gc();
		
	}// end of buildIndexTreeFromLeafs
	
	private InternalNode buildStaticTree ( DynamicInternalNode dyNode , int depth ) {
		if ( dyNode.children == null ) {
            if (verboos) {
                System.out.print(" " + dyNode.leaderID);
            }
			return null;
		} else if ( dyNode.children.size() == 0 ) {
			return null;
		} else {
            if (verboos) {
                for (int x = 0; x < depth; ++x) {
                    System.out.print("\t");
                }
                System.out.print("[" + dyNode.children.size() + "] \n");
            }
			depth++;
			InternalNode in = new InternalNode();
			in.children = new InternalNode[dyNode.children.size() + 1];
			in.leafs = new int[dyNode.children.size() + 1 ];
			if (dyNode.leaderID != -1) {
				in.leaderID = dyNode.leaderID;
			}
			boolean leafs = false;
			boolean child = false;
			for ( int i=0; i<dyNode.children.size(); ++i ) {
				InternalNode x = buildStaticTree( dyNode.children.get(i), depth );
				if (x == null) {
					leafs = true;
            in.leafs[i] = dyNode.children.get(i).leaderID;
        } else {
            child = true;
            in.children[i] = x;
        }
    }// for all children
			if ( !leafs ) {
                if(verboos) {
                    System.out.print(" Node is internal \n");
                }
                in.leafs = null;
			}
			if ( !child ) {
                if(verboos) {
                    System.out.print(" Node is leaf \n");
                }
				in.children = null;
			}
			if ( child && leafs ) {
				System.out.println("Ohh nooh .. we found both leafs and children in the same node :(");
			}
			return in;
		}
	}
	
	private DynamicInternalNode[] limitedDyRootSearch( SiftDescriptorContainer p, int limit, DynamicInternalNode root) {
		DynamicInternalNode[] ret = new DynamicInternalNode[treeA];
		DynamicInternalNode currNode = root;
		SiftKnnContainer qdesc = null;
        int currLevel = 1;
        // search the upper levels where we always only use k=1
        while ( currLevel <= limit ) {
            if ( currLevel == limit ) {
                        qdesc = new SiftKnnContainer(treeA);
			} else {
				qdesc = new SiftKnnContainer(1);
			}
			qdesc.SetQueryPoint( p );
			Iterator<DynamicInternalNode> i = currNode.children.iterator();
			int at = 0;
			while ( i.hasNext() ) {
				qdesc.add( this.leafs[i.next().leaderID],  at++);
			}
			if ( currLevel == limit ) {
				break;
			} else {
				currNode = currNode.children.get( qdesc.getKnnList()[0] );
				currLevel++;
			}
		}
		qdesc.sortKnnList();
		int[] res = qdesc.getKnnList();
		for (int i=0; i<res.length; ++i) {
			ret[i] = currNode.children.get(res[i]);
		}
        qdesc = null;
		return ret;
	}

	public SiftDescriptorContainer[] getTopStaticTreeCluster ( SiftDescriptorContainer p, int b ) {
		// the a return value
        SiftDescriptorContainer[] ret = null;
		// if the root is null, the tree is not setup properly.
		if ( root != null ) {
			// working pointer to root
			InternalNode curr = root;
			SiftKnnContainer qdesc;
			// while traversing internal nodes, i.e. children (leafs are only on the bottom level). 
			while ( curr.children != null) {
				// only find the single best matching cluster
				qdesc = new SiftKnnContainer( 1 );
				qdesc.SetQueryPoint(p);
				// scan the current node of the tree (starting at root) 
				for(int i=0; i<curr.children.length; ++i) {
					// add( point, id) is so that id can know the index of parent tree-node array in the results returned from the knn.
                    if (curr.children[i] == null) { continue;}
                    else {
                        qdesc.add(this.leafs[curr.children[i].leaderID], i);
                    }
				}
				// set the node to the next level.  NOTE we are skipping the qdesc.sortKNN() as it's only of size 1.
				curr = curr.children[qdesc.getKnnList()[0]];
                qdesc = null;
			}
			// now we should have curr set to the last internal level, so we now scan leafs for the b best clusters
			qdesc = new SiftKnnContainer(b);
			qdesc.SetQueryPoint(p);
			for (int i=0; i<curr.leafs.length; ++i) {
				//IndexClusterEntry ice = curr.leafs[i];
				//System.out.println( ice.centroid.id );
				//qdesc.add(curr.leafs[i], i);
                qdesc.add( this.leafs[curr.leafs[i]], curr.leafs[i]);
			}
			// now we need to sort as the knn is >1 
			qdesc.sortKnnList();
			int[] knn = qdesc.getKnnList();
            qdesc = null;
			// replace the internal index offset for the actual indexEntrys.
            // create a new return array of size b
            ret = new SiftDescriptorContainer[knn.length];
			for (int i=0; i<knn.length; ++i) {
				//System.out.println(knn[i]);
				ret[i] = this.leafs[knn[i]];
			}
		}
		return ret;
	}

    public String getTopStaticTreeClusterPath ( SiftDescriptorContainer p, int b ) {
        // the a return value
        StringBuffer ret = new StringBuffer();
        // if the root is null, the tree is not setup properly.
        if ( root != null ) {
            // working pointer to root
            InternalNode curr = root;
            SiftKnnContainer qdesc;
            // while traversing internal nodes, i.e. children (leafs are only on the bottom level).
            while ( curr.children != null) {
                // only find the single best matching cluster
                qdesc = new SiftKnnContainer( 10 );
                qdesc.SetQueryPoint(p);
                // scan the current node of the tree (starting at root)
                for(int i=0; i<curr.children.length; ++i) {
                    // add( point, id) is so that id can know the index of parent tree-node array in the results returned from the knn.
                    qdesc.add(this.leafs[curr.children[i].leaderID], i);
                }
                qdesc.sortKnnList();
                System.out.println(qdesc.toStringWithSscore());
                // set the node to the next level.
                ret.append(curr.children[qdesc.getKnnList()[0]].leaderID);
                ret.append("->");
                curr = curr.children[qdesc.getKnnList()[0]];
            }
            // now we should have curr set to the last internal level, so we now scan leafs for the b best clusters
            qdesc = new SiftKnnContainer( b );
            qdesc.SetQueryPoint(p);
            for (int i=0; i<curr.leafs.length; ++i) {
                qdesc.add(this.leafs[curr.leafs[i]], curr.leafs[i]);
            }
            // now we need to sort as the knn is >1
            qdesc.sortKnnList();
            System.out.println(qdesc.toStringWithSscore());
            System.out.println("############################");
            SiftKnnContainer.knnPair[] knn = qdesc.getknnPairArray();
            // replace the internal index offset for the actual indexEntrys.
            // create a new return array of size b
            ret.append("|");
            for (int i=0; i<knn.length; ++i) {
                ret.append(knn[i].pointID);
                ret.append(":");
                ret.append(knn[i].distance);
                ret.append(",");
            }
            ret.append("\n");
        }
        return ret.toString();
    }
}
