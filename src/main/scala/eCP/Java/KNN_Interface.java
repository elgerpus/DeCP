package eCP.Java;


public interface KNN_Interface {
	
	public String 	toString();
	public void 	SetQueryPoint( SiftDescriptorContainer queryPoint );
	public 			SiftDescriptorContainer getQueryPoint( );
	public void 	setK( int k );
    public int      getK();
	public int[] 	getKnnList();
	/**
	 * This method needs to be thread safe if b>1 in the search
	 * @param point Nearest neighbor candidate to consider
	 * @return returns true if the point was added to the KNN, false if rejected
	 */
    public boolean	add( SiftDescriptorContainer point );
	public boolean 	add( SiftDescriptorContainer point, int index);
    public boolean  add( int id, int distance );
	public void 	sortKnnList();
	public boolean 	hasBeenScaned();
	public void 	hasBeenScaned(boolean setValue);
	public void 	haltScan();
	public boolean	getDoScan();
	public int 		getB();
    public KNN_Interface merge( KNN_Interface a, KNN_Interface b );
    public boolean contains(int pointID);
}
