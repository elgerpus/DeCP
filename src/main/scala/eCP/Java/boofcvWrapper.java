package eCP.Java;

import boofcv.abst.feature.describe.ConfigSiftDescribe;
import boofcv.abst.feature.describe.ConfigSiftScaleSpace;
import boofcv.abst.feature.detdesc.DetectDescribePoint;
import boofcv.abst.feature.detect.interest.ConfigSiftDetector;
import boofcv.abst.feature.orientation.ConfigSiftOrientation;
import boofcv.factory.feature.detdesc.FactoryDetectDescribe;
import boofcv.struct.feature.SurfFeature;
import boofcv.struct.image.ImageFloat32;

import java.io.Serializable;


/**
 * Created by gylfi on 4/22/15.
 * Wrapper with settings to do Sift descriptors extraction using the BoofCV library
 */
public class boofcvWrapper implements Serializable {
    protected DetectDescribePoint<ImageFloat32, SurfFeature> sift;
    public byte[][] getSIFTDescriptorsAsByteArrays(ImageFloat32 img) {
        int numdim = 128;

        if (sift == null) {
            ConfigSiftScaleSpace    csss    = null; // new ConfigSiftScaleSpace()
            ConfigSiftDetector      csd     = new ConfigSiftDetector(2, 1.0F, 1000, 5);
            ConfigSiftOrientation   cso     = null; // new ConfigSiftOrientation()
            ConfigSiftDescribe      csdesc  = null; // new ConfigSiftDescribe()
            sift = FactoryDetectDescribe.sift(null, csd, null, null);
        }

        sift.detect(img);

        int numpts = sift.getNumberOfFeatures();
        byte [][] descs = new byte [numpts][numdim];
        for (int i=0; i<numpts; ++i) {
            double [] tmp = sift.getDescription(i).getValue();
            for (int d=0; d<tmp.length; ++d) {
                descs[i][d] = (byte) ( (512 * tmp[d]) - 128);
            }
        }
        return descs;
    }
}
