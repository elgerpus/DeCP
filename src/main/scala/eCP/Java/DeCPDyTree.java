package eCP.Java;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;


public class DeCPDyTree implements Serializable {
	private class DynamicInternalNode implements Serializable {
		public	ArrayList<DynamicInternalNode>	children 	= null;
		public 	int 		                    leaderID	= -1;
	}

    private static final long serialVersionUID = 1L;
	private DynamicInternalNode			root			= null;
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
        trimDyTree( dyRoot );
		root = dyRoot;
		
	}// end of buildIndexTreeFromLeafs

    void trimDyTree ( DynamicInternalNode curr ) {
        if( curr.children == null ) {
            return;
        }
        else {
            for (int i=0; curr.children.size()<i; ++i ) {
                trimDyTree( curr.children.get(i) );
            }
        }
        curr.children.trimToSize();
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
            DynamicInternalNode curr  = root;
			SiftKnnContainer qdesc;
			// while traversing internal nodes, i.e. children (leafs are only on the bottom level). 
			while ( curr.children != null) {
                if (curr.children.get(0).children == null ) {
                    // if this is the last level we break before scanning.
                    break;
                }
				// only find the single best matching cluster
				qdesc = new SiftKnnContainer( 1 );
				qdesc.SetQueryPoint(p);
				// scan the current node of the tree (starting at root)

				for(int i=0; i<curr.children.size(); ++i) {
					// add( point, id) is so that id can know the index of parent tree-node array in the results
					// returned from the knn.
					qdesc.add( this.leafs[curr.children.get(i).leaderID], i);
				}
				// set the node to the next level.  NOTE we are skipping the qdesc.sortKNN() as it's only of size 1.
				curr = curr.children.get(qdesc.getKnnList()[0]);
                qdesc = null;
			}
            // curr halts on the level below the bottom, now we scan it for the b best clusters
			qdesc = new SiftKnnContainer(b);
			qdesc.SetQueryPoint(p);

			for (int i=0; i<curr.children.size(); ++i) {
                qdesc.add( this.leafs[curr.children.get(i).leaderID], curr.children.get(i).leaderID);
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
        if (root != null) {
            // working pointer to root
            DynamicInternalNode curr = root;
            SiftKnnContainer qdesc;
            // while traversing internal nodes, i.e. children (leafs are only on the bottom level).
            while (curr.children != null) {
                if (curr.children.get(0).children == null) {
                    // if this is the last level we break before scanning.
                    break;
                }
                // only find the single best matching cluster
                qdesc = new SiftKnnContainer(3);
                qdesc.SetQueryPoint(p);

                // scan the current node of the tree (starting at root)
                for (int i = 0; i < curr.children.size(); ++i) {
                    // add( point, id) is so that id can know the index of parent tree-node array in the results
                    // returned from the knn.
                    qdesc.add(this.leafs[curr.children.get(i).leaderID], i);
                }
                qdesc.sortKnnList();
                ret.append(curr.children.get(qdesc.getKnnList()[0]).leaderID);
                if (qdesc.getKnnList().length > 2) {
                    ret.append("(");
                    ret.append( curr.children.get(qdesc.getKnnList()[1]).leaderID );
                    ret.append(")(");
                    ret.append( curr.children.get(qdesc.getKnnList()[2]).leaderID );
                    ret.append(")");
                }
                ret.append("->");
                curr = curr.children.get(qdesc.getKnnList()[0]);
                qdesc = null;
            }
            // curr halts on the level below the bottom, now we scan it for the b best clusters
            qdesc = new SiftKnnContainer(b);
            qdesc.SetQueryPoint(p);

            for (int i = 0; i < curr.children.size(); ++i) {
                qdesc.add(this.leafs[curr.children.get(i).leaderID], curr.children.get(i).leaderID);
            }
            // now we need to sort as the knn is >1
            qdesc.sortKnnList();
            int[] knn = qdesc.getKnnList();
            qdesc = null;
            // we do not need to "unwrapp" as the scan-loop (qdesc.add(...)) used the real ID not array ref.
            ret.append( knn[0] );
            for (int x = 1; x < knn.length; x++) {
                ret.append("(");
                ret.append( knn[x] );
                ret.append(")");
            }
        }
        return ret.toString();
    }
}
