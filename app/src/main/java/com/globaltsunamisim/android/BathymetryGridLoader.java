package com.globaltsunamisim.android;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Loads bathymetry generated from the original project's bundled GMRT and global data files. */
final class BathymetryGridLoader {
    private static final String TAG = "TsunamiBathy";
    private BathymetryGridLoader() { }

    static GridData load(Context context, boolean regional) throws IOException {
        final int nx, ny;
        final double lon0, lat0, dlon, dlat;
        final String path, label;
        if (regional) {
            nx=321; ny=241; lon0=100.0; lat0=0.0; dlon=0.25; dlat=0.25;
            path="data/nwpac_depth_025deg.f32"; label="西北太平洋 0.25° 地形网格";
        } else {
            nx=360; ny=144; lon0=-179.5; lat0=-71.5; dlon=1.0; dlat=1.0;
            path="data/global_depth_1deg.f32"; label="全球 1° 地形网格";
        }
        byte[] bytes=readAll(context.getAssets().open(path));
        int expected=nx*ny*4;
        if(bytes.length!=expected) throw new IOException("地形文件长度异常："+path+"（"+bytes.length+" bytes）");
        ByteBuffer buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] data=new float[nx*ny];
        for(int i=0;i<data.length;i++) data[i]=buffer.getFloat();
        Log.i(TAG,"Loaded "+path+" shape="+ny+"x"+nx);
        return new GridData(nx,ny,lon0,lat0,dlon,dlat,data,!regional,label);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try(InputStream in=input; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[32768]; int n;
            while((n=in.read(buf))!=-1) out.write(buf,0,n);
            return out.toByteArray();
        }
    }
}
