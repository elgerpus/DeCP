package eCP.Java;

import org.apache.hadoop.io.Writable;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;

public class SiftDescriptorContainer implements Serializable, Writable{
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	public int id;
	public byte[] vector;
	
	/**
	 * Constructors,
	 * Default to 128 dimensional byte vector 
	 * and MIN_VALUE of Int as the id.
	 */
	public SiftDescriptorContainer() {
		this.id = Integer.MIN_VALUE; 
		this.vector = new byte[128];
	}
	/**
	 * Constructors,
	 * Default to 128 dimensional byte vector
	 * @param 	id sets the id field of the vector
	 */
	public SiftDescriptorContainer(int id) {
        this();
        this.id = id;
	}
	/**
	 * Constructors, 
	 * @param id	id sets the id field of the vector
	 * @param size	size determines the dimensionality of the byte vector
	 */
	public SiftDescriptorContainer(int id, int size) {
		this.id = id;
		this.vector = new byte[size];
	} 
	/**
	 * Constructors, 
	 * @param id		id sets the id field of the vector
	 * @param vector	already loaded byte array
	 */
	public SiftDescriptorContainer( int id, byte[] vector ) {
		this.id = id;
		this.vector = vector;
	}

    @Override
    public void write(DataOutput dataOutput) throws IOException {
        dataOutput.writeInt(this.id);
        for (int i=0; i<vector.length; i++) {
            dataOutput.writeByte( vector[i] );
        }
    }

    @Override
    public void readFields(DataInput dataInput) throws IOException {
        this.id = dataInput.readInt();
        dataInput.readFully( this.vector );
    }

    public String toString() {
       return "id:"+this.id;
    }
}
