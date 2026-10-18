package com.globaltsunamisim.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Mobile port of the original Global Tsunami Simulator's linear shallow-water solver.
 * Arakawa-C staggered grid, spherical lon/lat metrics, forward-backward time integration,
 * Coriolis terms, shoreline no-normal-flow, a boundary sponge, weak biharmonic filter and
 * implicit quadratic bottom-friction damping. This class is Android-framework independent.
 */
final class TsunamiModel {
    static final double G = 9.80665;
    static final double OMEGA = 7.2921159e-5;
    static final double EARTH_RADIUS = 6_371_000.0;
    static final double DEG_TO_KM = Math.PI * EARTH_RADIUS / 180_000.0;

    final GridData grid;
    final int nx, ny, cellCount;
    final double[] eta, u, v;
    final double[] h, hu, hv, sigma, filterW;
    final double[] cosLat, cosLatV, dx, fLat, fLatV, sigmaU, sigmaV;
    final double dy, dt, cMax;
    final boolean periodicLon;
    final double filterAlpha = 0.02, filterShallow = 1.5, frictionCd = 0.003, frictionHMin = 5.0;
    final double[] lap, lap2;
    final int[] coastIndices;
    final double[] coastLon, coastLat, coastMax, coastArrival, coastPeak;
    final String sourceNote;
    double timeSeconds = 0;
    long steps = 0;
    private boolean stopped = false;

    static final class Params {
        double magnitude, depthKm, lon, lat, strike, dip, rake = 90.0;
        double durationHours = 6.0;
        Params(double magnitude, double depthKm, double lon, double lat, double strike, double dip, double durationHours) {
            this.magnitude = magnitude; this.depthKm = depthKm; this.lon = lon; this.lat = lat;
            this.strike = strike; this.dip = dip; this.durationHours = durationHours;
        }
    }

    static final class Alert {
        final String name, level, color;
        final double heightMeters, arrivalSeconds, peakSeconds;
        Alert(String name, double h, String level, String color, double arrival, double peak) {
            this.name = name; this.heightMeters = h; this.level = level; this.color = color;
            this.arrivalSeconds = arrival; this.peakSeconds = peak;
        }
    }

    private static final class AlertBox {
        final String name, category;
        final double west, east, south, north;
        AlertBox(String n, String c, double w, double e, double s, double no) {
            name=n; category=c; west=w; east=e; south=s; north=no;
        }
        boolean contains(double lon, double lat) { return lon >= west && lon <= east && lat >= south && lat <= north; }
        boolean intersects(GridData.Bounds b) { return east >= b.west && west <= b.east && north >= b.south && south <= b.north; }
    }

    private static final AlertBox[] BOXES = new AlertBox[] {
        new AlertBox("中国东南沿海", "china", 105,123,18,32),
        new AlertBox("中国黄渤海沿岸", "china",117,127,32,41),
        new AlertBox("台湾沿岸", "china",119,123,21,26),
        new AlertBox("菲律宾群岛", "nwpac",116,127,4,20),
        new AlertBox("越南中部沿岸", "nwpac",105,111,8,22),
        new AlertBox("马来—印尼东部", "nwpac",111,141,-10,6),
        new AlertBox("朝鲜半岛沿岸", "japan",124,130.5,33,40),
        new AlertBox("琉球群岛", "japan",122,132,23,30),
        new AlertBox("日本九州", "japan",128,132.5,30,34.5),
        new AlertBox("日本四国", "japan",132,135.5,32.5,35),
        new AlertBox("日本本州太平洋沿岸", "japan",134,142.5,33,41.5),
        new AlertBox("日本北海道与东北", "japan",139,147,41,46),
        new AlertBox("日本海沿岸", "japan",130,140.2,34,46),
        new AlertBox("俄罗斯远东沿岸", "japan",130,145,45,55),
        new AlertBox("库页岛与鄂霍次克海", "japan",141,156,45,56),
        new AlertBox("千岛群岛", "japan",145,159,43,51.5),
        new AlertBox("堪察加半岛", "japan",154,165,50,62),
        new AlertBox("马里亚纳群岛", "mariana",140,150,10,22),
        new AlertBox("帕劳与密克罗尼西亚", "mariana",130,165,-2,10),
        new AlertBox("新几内亚北部", "global",130,150,-12,-1),
        new AlertBox("澳大利亚东岸", "global",145,160,-40,-10),
        new AlertBox("新西兰沿岸", "global",165,180,-48,-33),
        new AlertBox("夏威夷群岛", "global",-161,-154,18,23),
        new AlertBox("阿拉斯加与阿留申", "global",-180,-135,50,62.5),
        new AlertBox("阿留申西部", "global",163,180,48,58),
        new AlertBox("北美西岸（阿拉斯加—加州）", "global",-135,-116,30,60),
        new AlertBox("中美与南美西岸", "global",-90,-70,-45,20),
        new AlertBox("智利沿岸", "global",-80,-68,-50,-15)
    };

    TsunamiModel(GridData grid, Params params) {
        if (grid == null) throw new IllegalArgumentException("缺少海底地形数据");
        if (params.magnitude < 5.0 || params.magnitude > 9.9) throw new IllegalArgumentException("震级范围须为 Mw 5.0–9.9");
        if (params.depthKm < 1.0 || params.depthKm > 700.0) throw new IllegalArgumentException("震源深度须为 1–700 km");
        if (params.lat < -72.0 || params.lat > 72.0) throw new IllegalArgumentException("纬度须在 -72° 到 72° 之间");
        if (params.durationHours < 0.25 || params.durationHours > 24.0) throw new IllegalArgumentException("模拟时长须为 0.25–24 小时");
        this.grid=grid; this.nx=grid.nx; this.ny=grid.ny; this.cellCount=nx*ny; this.periodicLon=grid.periodicLon;
        this.eta = new double[cellCount];
        this.u = new double[ny * (nx + 1)];
        this.v = new double[(ny + 1) * nx];
        this.h = new double[cellCount];
        this.hu = new double[ny * (nx + 1)];
        this.hv = new double[(ny + 1) * nx];
        this.sigma = new double[cellCount];
        this.filterW = new double[cellCount];
        this.cosLat = new double[ny];
        this.cosLatV = new double[ny + 1];
        this.dx = new double[ny];
        this.fLat = new double[ny];
        this.fLatV = new double[ny + 1];
        this.sigmaU = new double[u.length];
        this.sigmaV = new double[v.length];
        this.lap = new double[cellCount];
        this.lap2 = new double[cellCount];

        double dlon = Math.toRadians(Math.abs(grid.dLon));
        double dlat = Math.toRadians(Math.abs(grid.dLat));
        dy = EARTH_RADIUS * dlat;
        double maxDepth = 1.0;
        for (int k=0; k<cellCount; k++) {
            double dep = grid.depth[k];
            h[k] = dep > 10.0 ? dep : 0.0;
            if (h[k] > maxDepth) maxDepth=h[k];
            filterW[k] = h[k] > 0 ? Math.exp(-h[k] / 200.0) : 0.0;
        }
        double minDx = Double.POSITIVE_INFINITY;
        for (int j=0; j<ny; j++) {
            double phi = Math.toRadians(grid.lat(j));
            cosLat[j] = Math.max(Math.cos(phi), 1e-4);
            dx[j] = EARTH_RADIUS * dlon * cosLat[j];
            minDx = Math.min(minDx, dx[j]);
            fLat[j] = 2.0 * OMEGA * Math.sin(phi);
            double w = Math.max(2.0 / Math.abs(grid.dLat), 1.0);
            double dist = Math.min(j, ny - 1 - j) / w;
            double wy = clamp(dist,0,1);
            for (int i=0; i<nx; i++) {
                double wx;
                if (periodicLon) wx=1.0;
                else wx=clamp(Math.min(i, nx-1-i) / Math.max(2.0/Math.abs(grid.dLon),1.0),0,1);
                double ramp = 1.0 - Math.min(wx,wy);
                sigma[j*nx+i] = h[j*nx+i] > 0 ? ramp*ramp/180.0 : 0.0;
            }
        }
        for (int j=0; j<=ny; j++) {
            if (j==0) { cosLatV[j]=cosLat[0]; fLatV[j]=fLat[0]; }
            else if (j==ny) { cosLatV[j]=cosLat[ny-1]; fLatV[j]=fLat[ny-1]; }
            else { cosLatV[j]=0.5*(cosLat[j-1]+cosLat[j]); fLatV[j]=0.5*(fLat[j-1]+fLat[j]); }
        }
        for (int j=0; j<ny; j++) {
            int rowU=j*(nx+1), row=j*nx;
            if (periodicLon && nx>1 && h[row+nx-1]>0 && h[row]>0) {
                hu[rowU]=(h[row+nx-1]+h[row])*0.5;
                hu[rowU+nx]=hu[rowU];
            }
            for (int i=1; i<nx; i++) {
                int a=row+i-1,b=row+i;
                if (h[a]>0 && h[b]>0) hu[rowU+i]=0.5*(h[a]+h[b]);
            }
            if (!periodicLon) { hu[rowU]=0.0; hu[rowU+nx]=0.0; }
        }
        for (int j=1; j<ny; j++) {
            int rowV=j*nx, top=(j-1)*nx, bot=j*nx;
            for (int i=0; i<nx; i++) if (h[top+i]>0 && h[bot+i]>0) hv[rowV+i]=0.5*(h[top+i]+h[bot+i]);
        }
        for (int j=0; j<ny; j++) {
            int rowU=j*(nx+1), row=j*nx;
            for (int i=1; i<nx; i++) sigmaU[rowU+i]=0.5*(sigma[row+i-1]+sigma[row+i]);
            if (periodicLon) {
                sigmaU[rowU]=0.5*(sigma[row+nx-1]+sigma[row]);
                sigmaU[rowU+nx]=sigmaU[rowU];
            }
        }
        for (int j=1; j<ny; j++) for (int i=0; i<nx; i++) {
            sigmaV[j*nx+i]=0.5*(sigma[(j-1)*nx+i]+sigma[j*nx+i]);
        }
        cMax=Math.sqrt(G*maxDepth);
        dt=0.45*Math.min(minDx,dy)/cMax;

        double sourceLon=params.lon, sourceLat=params.lat;
        if (periodicLon) sourceLon=wrapLon(sourceLon);
        if ((!periodicLon && (sourceLon < grid.lon0-grid.dLon*0.5-1e-6 || sourceLon > grid.lon(nx-1)+grid.dLon*0.5+1e-6)) ||
            sourceLat < grid.lat0-grid.dLat*0.5-1e-6 || sourceLat > grid.lat(ny-1)+grid.dLat*0.5+1e-6) {
            throw new IllegalArgumentException("震中超出当前地形模型覆盖范围，请选择全球模式或调整震中。当前网格："+
                    fmt(grid.lon0)+"–"+fmt(grid.lon(nx-1))+"° 经度，"+fmt(grid.lat0)+"–"+fmt(grid.lat(ny-1))+"° 纬度。");
        }
        int sourceI=nearestLon(sourceLon), sourceJ=nearestLat(sourceLat);
        String note="";
        if (h[sourceJ*nx+sourceI] <= 0) {
            int foundI=-1,foundJ=-1; double best=Double.POSITIVE_INFINITY;
            for (int r=1; r<=12; r++) {
                for (int dj=-r;dj<=r;dj++) for (int di=-r;di<=r;di++) {
                    if (Math.max(Math.abs(di),Math.abs(dj))!=r) continue;
                    int jj=sourceJ+dj, ii=sourceI+di;
                    if (jj<0||jj>=ny||ii<0||ii>=nx) continue;
                    if (h[jj*nx+ii]<=0) continue;
                    double dd=Math.pow((grid.lon(ii)-sourceLon)*111.32*Math.cos(Math.toRadians(sourceLat)),2)+Math.pow((grid.lat(jj)-sourceLat)*110.57,2);
                    if (dd<best) { best=dd; foundI=ii; foundJ=jj; }
                }
                if (foundI>=0) break;
            }
            if (foundI<0) throw new IllegalArgumentException("震中位于陆地或浅水区，附近网格未找到合适海域。请修改震中经纬度。");
            sourceLon=grid.lon(foundI); sourceLat=grid.lat(foundJ);
            note="震中格点在陆地/浅水，已移动到最近海域格点（约"+fmt(Math.sqrt(best))+" km）。";
        }
        sourceNote=note;
        initializeSource(params,sourceLon,sourceLat);
        ArrayList<Integer> ci=new ArrayList<>(); ArrayList<Double> cln=new ArrayList<>(),cla=new ArrayList<>();
        for (int j=1;j<ny-1;j++) for (int i=0;i<nx;i++) {
            int k=j*nx+i; if (h[k]<=0) continue;
            boolean nearLand = h[k-nx]<=0 || h[k+nx]<=0;
            if (i>0) nearLand |= h[k-1]<=0;
            else if (periodicLon) nearLand |= h[j*nx+nx-1]<=0;
            if (i<nx-1) nearLand |= h[k+1]<=0;
            else if (periodicLon) nearLand |= h[j*nx]<=0;
            if (nearLand) { ci.add(k); cln.add(grid.lon(i)); cla.add(grid.lat(j)); }
        }
        coastIndices=new int[ci.size()]; coastLon=new double[ci.size()]; coastLat=new double[ci.size()];
        coastMax=new double[ci.size()]; coastArrival=new double[ci.size()]; coastPeak=new double[ci.size()];
        Arrays.fill(coastArrival,Double.NaN); Arrays.fill(coastPeak,Double.NaN);
        for(int q=0;q<ci.size();q++){coastIndices[q]=ci.get(q);coastLon[q]=cln.get(q);coastLat[q]=cla.get(q);}
        trackCoast();
    }

    private void initializeSource(Params p, double sourceLon, double sourceLat) {
        double mw=p.magnitude;
        double lengthKm=Math.pow(10.0,-2.44+0.59*mw);
        double widthKm=Math.pow(10.0,-1.01+0.32*mw);
        double moment=Math.pow(10.0,1.5*mw+9.1);
        double zoneRamp=alaskaRamp(mw,sourceLon,sourceLat);
        if (zoneRamp>0) widthKm=Math.max(widthKm,200.0*zoneRamp);
        double slip=moment/(4.0e10*(lengthKm*1000.0)*(widthKm*1000.0));
        double zoneAmp=1.0+(3.33-1.0)*zoneRamp;
        double strike=Math.toRadians(p.strike), dip=Math.toRadians(clamp(p.dip,1.0,89.0)), rake=Math.toRadians(p.rake);
        double sE=Math.sin(strike), sN=Math.cos(strike), dE=Math.cos(strike), dN=-Math.sin(strike);
        double halfW=0.5*widthKm*Math.cos(dip);
        double sigmaRef=Math.max(halfW,5.0);
        double sigma=sigmaRef+0.5*p.depthKm;
        double latScale=Math.max(Math.cos(Math.toRadians(sourceLat)),0.2);
        double localDx=Math.abs(grid.dLon)*DEG_TO_KM*latScale;
        double localDy=Math.abs(grid.dLat)*DEG_TO_KM;
        sigma=Math.max(sigma,1.5*Math.max(localDx,localDy));
        double zTop=Math.max(p.depthKm,0.5);
        double zBot=zTop+widthKm*Math.sin(dip);
        // Keep the finite-thickness and top-depth calculation explicit, matching the desktop source model.
        if (!(zBot>zTop)) throw new IllegalArgumentException("断层几何参数无效");
        double depthScale=sigmaRef/sigma;
        double amp=slip*Math.sin(dip)*Math.abs(Math.sin(rake))*0.6*zoneAmp*depthScale;
        double halfL=0.5*lengthKm;
        double lat0=Math.toRadians(sourceLat);
        for(int j=0;j<ny;j++) {
            double y=(grid.lat(j)-sourceLat)*DEG_TO_KM;
            int row=j*nx;
            for(int i=0;i<nx;i++) {
                int k=row+i;
                // Keep the source field on land for the first filter pass, matching the
                // desktop solver; the shoreline mask zeros land cells after that pass.
                double dlon=grid.lon(i)-sourceLon;
                if (periodicLon) { while(dlon>180) dlon-=360; while(dlon<-180) dlon+=360; }
                double x=dlon*DEG_TO_KM*Math.cos(lat0);
                double xi=x*dE+y*dN;
                double zeta=x*sE+y*sN;
                double t=xi/sigma;
                double kernel=-Math.sqrt(Math.E)*t*Math.exp(-0.5*t*t);
                double taper=0.0;
                if(Math.abs(zeta)<halfL) taper=0.5*(1.0+Math.cos(Math.PI*zeta/halfL))*2.0;
                eta[k]=amp*kernel*taper;
            }
        }
    }

    private static double alaskaRamp(double mw,double lon,double lat) {
        double x=wrapLon(lon);
        boolean inside=(x>=-170&&x<=-133&&lat>=47&&lat<=62.5)||
                (x>=-180&&x<=-160&&lat>=48&&lat<=57.5)||(x>=163&&x<=180&&lat>=48&&lat<=57.5);
        if(!inside) return 0;
        return clamp((mw-8.5)/(9.0-8.5),0,1);
    }
    private int nearestLon(double val){return clampInt((int)Math.round((val-grid.lon0)/grid.dLon),0,nx-1);}
    private int nearestLat(double val){return clampInt((int)Math.round((val-grid.lat0)/grid.dLat),0,ny-1);}
    private static int clampInt(int x,int lo,int hi){return Math.max(lo,Math.min(hi,x));}
    private static double clamp(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}
    private static double wrapLon(double lon){while(lon>180)lon-=360;while(lon < -180)lon+=360;return lon;}
    private static String fmt(double x){return String.format(java.util.Locale.US,"%.2f",x);}

    double totalHours(double hours){return hours*3600.0;}
    boolean isStopped(){return stopped;}
    void requestStop(){stopped=true;}

    /** Advance one stable CFL-limited shallow-water step. */
    void step() {
        final double dtime=dt;
        // Momentum equation, east-west face velocities u.
        for(int j=0;j<ny;j++) {
            int row=j*nx, rowU=j*(nx+1);
            for(int i=1;i<nx;i++) {
                double grad=(eta[row+i]-eta[row+i-1])/dx[j];
                double vcross=0.25*(v[j*nx+i-1]+v[j*nx+i]+v[(j+1)*nx+i-1]+v[(j+1)*nx+i]);
                int uf=rowU+i;
                u[uf]+=dtime*(-G*grad+fLat[j]*vcross);
                u[uf]-=dtime*sigmaU[uf]*u[uf];
            }
            if(periodicLon && nx>1) {
                double grad=(eta[row]-eta[row+nx-1])/dx[j];
                double vcross=0.25*(v[j*nx+nx-1]+v[j*nx]+v[(j+1)*nx+nx-1]+v[(j+1)*nx]);
                double w=u[rowU]+dtime*(-G*grad+fLat[j]*vcross);
                w-=dtime*sigmaU[rowU]*w;
                u[rowU]=w;u[rowU+nx]=w;
            }
        }
        // Momentum equation, north-south face velocities v.
        for(int j=1;j<ny;j++) {
            int rowV=j*nx, topU=(j-1)*(nx+1), botU=j*(nx+1), topEta=(j-1)*nx, botEta=j*nx;
            double fv=fLatV[j];
            for(int i=0;i<nx;i++) {
                double grad=(eta[botEta+i]-eta[topEta+i])/dy;
                double ucross=0.25*(u[topU+i]+u[topU+i+1]+u[botU+i]+u[botU+i+1]);
                int vf=rowV+i;
                v[vf]+=dtime*(-G*grad-fv*ucross);
                v[vf]-=dtime*sigmaV[vf]*v[vf];
            }
        }
        // Continuity equation in conservative flux-divergence form.
        for(int j=0;j<ny;j++) {
            int row=j*nx, rowU=j*(nx+1), rowTopV=(j+1)*nx, rowBottomV=j*nx;
            double cosj=cosLat[j], xdx=dx[j];
            for(int i=0;i<nx;i++) {
                double right=hu[rowU+i+1]*u[rowU+i+1];
                double left=hu[rowU+i]*u[rowU+i];
                double div=(right-left)/xdx;
                double top=hv[rowTopV+i]*v[rowTopV+i]*cosLatV[j+1];
                double bottom=hv[rowBottomV+i]*v[rowBottomV+i]*cosLatV[j];
                div+=(top-bottom)/(dy*cosj);
                int k=row+i;
                eta[k]-=dtime*div;
                eta[k]-=dtime*sigma[k]*eta[k];
                // Desktop parity: keep the initial source values on land until the
                // first biharmonic filter pass, where the land mask is applied.
            }
        }
        // Semi-implicit quadratic bottom drag, strongest in shallow water.
        for(int j=0;j<ny;j++) {
            int ru=j*(nx+1);
            for(int i=1;i<nx;i++) {
                int k=ru+i;
                double r=frictionCd*Math.abs(u[k])/Math.max(hu[k],frictionHMin);
                u[k]/=(1.0+r*dtime);
            }
            if(periodicLon) {
                double r=frictionCd*Math.abs(u[ru])/Math.max(hu[ru],frictionHMin);
                u[ru]/=(1.0+r*dtime); u[ru+nx]=u[ru];
            }
        }
        for(int j=1;j<ny;j++) {
            int rv=j*nx;
            for(int i=0;i<nx;i++) {
                int k=rv+i;
                double r=frictionCd*Math.abs(v[k])/Math.max(hv[k],frictionHMin);
                v[k]/=(1.0+r*dtime);
            }
        }
        steps++;
        timeSeconds+=dtime;
        if((steps-1)%4==0) smoothEta();
    }

    private void smoothEta() {
        // Mean-neighbor Laplacian, then a weak biharmonic correction. Neumann edge padding; periodic longitude for global mode.
        for(int j=0;j<ny;j++) for(int i=0;i<nx;i++) {
            int k=j*nx+i;
            int jm=Math.max(0,j-1), jp=Math.min(ny-1,j+1);
            int im=i==0?(periodicLon?nx-1:0):i-1;
            int ip=i==nx-1?(periodicLon?0:nx-1):i+1;
            lap[k]=0.25*(eta[jm*nx+i]+eta[jp*nx+i]+eta[j*nx+im]+eta[j*nx+ip])-eta[k];
        }
        for(int j=0;j<ny;j++) for(int i=0;i<nx;i++) {
            int k=j*nx+i;
            int jm=Math.max(0,j-1), jp=Math.min(ny-1,j+1);
            int im=i==0?(periodicLon?nx-1:0):i-1;
            int ip=i==nx-1?(periodicLon?0:nx-1):i+1;
            lap2[k]=0.25*(lap[jm*nx+i]+lap[jp*nx+i]+lap[j*nx+im]+lap[j*nx+ip])-lap[k];
        }
        for(int k=0;k<cellCount;k++) if(h[k]>0) eta[k]-=filterAlpha*(1.0+filterShallow*filterW[k])*lap2[k]; else eta[k]=0;
    }

    /** Track alongshore maxima and the first sampled positive-water-level crossing at 2 cm. */
    void trackCoast() {
        for(int q=0;q<coastIndices.length;q++) {
            double e=eta[coastIndices[q]];
            if(e>=0.02 && Double.isNaN(coastArrival[q])) coastArrival[q]=timeSeconds;
            if(e>coastMax[q]) { coastMax[q]=e; coastPeak[q]=timeSeconds; }
        }
    }

    double maxAbsEta() {
        double m=0; for(int i=0;i<cellCount;i++) if(h[i]>0) m=Math.max(m,Math.abs(eta[i])); return m;
    }

    List<Alert> getAlerts(String focus) {
        ArrayList<Alert> out=new ArrayList<>();
        GridData.Bounds focusBounds=GridData.viewBounds(focus);
        for(AlertBox box:BOXES) {
            if(("china".equals(focus) || "japan".equals(focus) || "mariana".equals(focus) || "nwpac".equals(focus)) && !box.intersects(focusBounds)) continue;
            if("china".equals(focus) && !"china".equals(box.category) && !"nwpac".equals(box.category)) continue;
            if("japan".equals(focus) && !"japan".equals(box.category)) continue;
            if("mariana".equals(focus) && !"mariana".equals(box.category) && !"nwpac".equals(box.category)) continue;
            double max=0,arr=Double.NaN,peak=Double.NaN;
            boolean matched=false;
            for(int q=0;q<coastIndices.length;q++) {
                double x=wrapLon(coastLon[q]), y=coastLat[q];
                if(!box.contains(x,y)) continue;
                matched=true;
                if(!Double.isNaN(coastArrival[q]) && (Double.isNaN(arr)||coastArrival[q]<arr)) arr=coastArrival[q];
                if(coastMax[q]>max) { max=coastMax[q]; peak=coastPeak[q]; }
            }
            if(matched && max>=0.02) {
                String[] classification=classify(max);
                out.add(new Alert(box.name,max,classification[0],classification[1],arr,peak));
            }
        }
        Collections.sort(out,new Comparator<Alert>() { @Override public int compare(Alert a, Alert b){ return Double.compare(b.heightMeters,a.heightMeters); } });
        return out;
    }

    static String[] classify(double h) {
        if(h>10.0) return new String[]{"巨大津波（模型）","#4b176d"};
        if(h>=3.0) return new String[]{"大津波警报级（模型）","#9238a8"};
        if(h>=1.0) return new String[]{"津波警报级（模型）","#e53b42"};
        if(h>0.2) return new String[]{"注意报级（模型）","#edc746"};
        return new String[]{"微弱波动（模型）","#4b9cd3"};
    }
}
