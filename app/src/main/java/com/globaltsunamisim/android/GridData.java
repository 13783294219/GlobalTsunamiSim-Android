package com.globaltsunamisim.android;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/** Bathymetry on a regular latitude/longitude grid. Depth is positive in the ocean and negative on land. */
final class GridData {
    final int nx, ny;
    final double lon0, lat0, dLon, dLat;
    final float[] depth;
    final boolean periodicLon;
    final String resolutionLabel;

    GridData(int nx, int ny, double lon0, double lat0, double dLon, double dLat,
             float[] depth, boolean periodicLon, String resolutionLabel) {
        if (depth == null || depth.length != nx * ny) throw new IllegalArgumentException("地形网格尺寸不匹配");
        this.nx = nx; this.ny = ny; this.lon0 = lon0; this.lat0 = lat0;
        this.dLon = dLon; this.dLat = dLat; this.depth = depth;
        this.periodicLon = periodicLon; this.resolutionLabel = resolutionLabel;
    }

    double lon(int i) { return lon0 + i * dLon; }
    double lat(int j) { return lat0 + j * dLat; }
    int index(int j, int i) { return j * nx + i; }
    float depthAt(int j, int i) { return depth[index(j, i)]; }

    static final class Bounds {
        final double west, east, south, north;
        Bounds(double west, double east, double south, double north) {
            this.west = west; this.east = east; this.south = south; this.north = north;
        }
    }

    static Bounds viewBounds(String id) {
        if ("china".equals(id)) return new Bounds(105, 130, 15, 42);
        if ("japan".equals(id)) return new Bounds(120, 150, 20, 48);
        if ("mariana".equals(id)) return new Bounds(130, 165, 0, 30);
        if ("nwpac".equals(id)) return new Bounds(100, 180, 0, 60);
        return new Bounds(-180, 180, -72, 72);
    }

    /** Render data is downsampled from the solver grid. The output raster is north-to-south. */
    float[] preview(Bounds bounds, int outW, int outH, boolean useDepth, double[] eta) {
        float[] out = new float[outW * outH];
        for (int y = 0; y < outH; y++) {
            double latTarget = bounds.north - (bounds.north - bounds.south) * y / Math.max(1.0, outH - 1.0);
            int j = nearestLat(latTarget);
            for (int x = 0; x < outW; x++) {
                double lonTarget = bounds.west + (bounds.east - bounds.west) * x / Math.max(1.0, outW - 1.0);
                int i = nearestLon(lonTarget);
                if (j < 0 || i < 0) { out[y * outW + x] = Float.NaN; continue; }
                int idx = j * nx + i;
                out[y * outW + x] = useDepth ? depth[idx] : (eta == null ? 0f : (float) eta[idx]);
            }
        }
        return out;
    }

    int nearestLat(double value) {
        double q=(value-lat0)/dLat;
        if(q < -0.5 || q > ny-0.5) return -1;
        int j=(int)Math.round(q);
        return Math.max(0,Math.min(ny-1,j));
    }
    int nearestLon(double value) {
        if (periodicLon) {
            double width = nx * dLon;
            while (value < lon0) value += width;
            while (value >= lon0 + width) value -= width;
            int wrapped = (int) Math.round((value - lon0) / dLon);
            return ((wrapped % nx) + nx) % nx;
        }
        int i = (int) Math.round((value - lon0) / dLon);
        return i >= 0 && i < nx ? i : -1;
    }

    static byte[] floatsToLittleEndian(float[] values) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) b.putFloat(v);
        return b.array();
    }
}
