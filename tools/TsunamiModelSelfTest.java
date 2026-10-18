package com.globaltsunamisim.android;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Standalone numerical smoke test; requires only a JDK, not Android SDK. */
public final class TsunamiModelSelfTest {
    private static GridData readGrid(String path, int nx, int ny, double lon0, double lat0,
                                     double step, boolean periodic) throws Exception {
        byte[] b = Files.readAllBytes(Paths.get(path));
        if (b.length != nx*ny*4) throw new AssertionError("unexpected data length: "+path);
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        float[] z = new float[nx*ny]; for(int i=0;i<z.length;i++) z[i]=bb.getFloat();
        return new GridData(nx,ny,lon0,lat0,step,step,z,periodic,path);
    }
    private static void finite(TsunamiModel model) {
        for (double x : model.eta) if (!Double.isFinite(x)) throw new AssertionError("non-finite eta");
        double m=model.maxAbsEta(); if(!Double.isFinite(m)) throw new AssertionError("non-finite max eta");
    }
    public static void main(String[] args) throws Exception {
        String data="app/src/main/assets/data/";
        GridData regional=readGrid(data+"nwpac_depth_025deg.f32",321,241,100,0,.25,false);
        TsunamiModel.Params p=new TsunamiModel.Params(8.8,25,142.4,38.3,200,15,6);
        // Fixed regression point from the original desktop Python SWE solver using this exact grid.
        TsunamiModel reference=new TsunamiModel(regional,p);
        for(int i=0;i<100;i++) reference.step();
        double referencePoint=reference.eta[155*regional.nx+167];
        if(Math.abs(referencePoint-3.5812647)>2e-5)
            throw new AssertionError("100-step desktop reference mismatch: "+referencePoint);
        System.out.printf(Locale.US,"REFERENCE PASS steps=%d eta[lat=38.75,lon=141.75]=%.7fm%n",reference.steps,referencePoint);
        long start=System.nanoTime();
        TsunamiModel model=new TsunamiModel(regional,p);
        double initialMax=model.maxAbsEta();
        if(initialMax<0.05) throw new AssertionError("source initial field is implausibly small");
        finite(model);
        int n=(int)Math.ceil(6*3600/model.dt);
        int recordEvery=Math.max(1,(int)Math.round(300/model.dt));
        for(int i=0;i<n;i++) { model.step(); if(i%recordEvery==0)model.trackCoast(); if(i%100==0)finite(model); }
        model.trackCoast(); finite(model);
        List<TsunamiModel.Alert> alerts=model.getAlerts("japan");
        if(alerts.isEmpty()) throw new AssertionError("no data-derived coastal records produced for 6h Japan run");
        System.out.printf(Locale.US,"REGIONAL PASS grid=%dx%d dt=%.3fs steps=%d simulated=%.2fh sourceInitialMax=%.3fm finalMaxAbs=%.3fm japanRecords=%d elapsed=%.2fs%n",
                regional.nx,regional.ny,model.dt,model.steps,model.timeSeconds/3600,
                initialMax,model.maxAbsEta(),alerts.size(),(System.nanoTime()-start)/1e9);
        GridData global=readGrid(data+"global_depth_1deg.f32",360,144,-179.5,-71.5,1.0,true);
        TsunamiModel gm=new TsunamiModel(global,new TsunamiModel.Params(8.8,25,142.4,38.3,200,15,1));
        int gn=(int)Math.ceil(3600/gm.dt);for(int i=0;i<gn;i++)gm.step();gm.trackCoast();finite(gm);
        if(gm.steps<1)throw new AssertionError("global model didn't step");
        System.out.printf(Locale.US,"GLOBAL PASS grid=%dx%d dt=%.3fs steps=%d simulated=%.2fm maxAbs=%.3fm%n",
                global.nx,global.ny,gm.dt,gm.steps,gm.timeSeconds/60,gm.maxAbsEta());
    }
}
