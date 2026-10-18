# 数据与第三方资源来源

本程序代码为 MIT 许可，但**随包或运行中获取的数据**有各自的来源与使用条款：

| 数据 / 资源 | 来源 | 许可与要求 |
|---|---|---|
| `data/nwpac_bathy.nc`、`data/global_bathy.nc` | [GMRT](https://www.gmrt.org/)（Marine Geoscience Data System，Columbia University / Rutgers） | 免费用于科研与教育，**使用时请引用 GMRT**；不得用于航行安全 |
| 全球地形原始来源（GMRT 内部） | 多源合成（含 GEBCO、SRTM15+、船测数据） | 见 GMRT 说明 |
| Natural Earth 海岸线 / 国界 / 省界 | [Natural Earth](https://www.naturalearthdata.com/) | Public Domain（无需署名，仍建议致谢） |
| 地震目录：USGS | [earthquake.usgs.gov](https://earthquake.usgs.gov/) | 美国联邦政府作品，公有领域 |
| 地震目录：中国地震台网速报 | [中国地震台网](https://www.ceic.ac.cn/)（经 [Wolfx](https://api.wolfx.jp/) 公开镜像获取） | 数据版权归中国地震台网；本程序仅作科普展示 |
| 地震目录：EMSC | [seismicportal.eu](https://www.seismicportal.eu/) | 见 EMSC 使用条款 |
| 地震目录：日本气象厅 JMA | [JMA](https://www.jma.go.jp/)（经 Wolfx 镜像） | 数据版权归 JMA |
| ffmpeg（可选，用于 MP4 导出） | [ffmpeg.org](https://ffmpeg.org/) | 独立程序，遵循其自身许可（GPL/LGPL），**本仓库不包含该二进制** |

## 建议引用

若在教学、论文或科普作品中使用了本程序或其地形数据，建议注明：

> 海啸传播模拟使用 Global Tsunami Simulator（MIT 许可）完成，
> 海底地形来自 GMRT（Marine Geoscience Data System）。

并请同时引用 GMRT：

> Ryan, W. B. F., et al. (2009). Global Multi-Resolution Topography synthesis.
> Geochemistry, Geophysics, Geosystems, 10, Q03014.
