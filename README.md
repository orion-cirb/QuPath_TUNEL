# QuPath_TUNEL

**Developed for:** Thassadite

**Team:** De Thé

**Date:** July 2022

**Software:** QuPath

### Images description

2D images of liver sections taken with the Axioscan

2 stainings:
  1. Hematoxylin: nuclei
  2. DAB: DAB cells
  

### Plugin description

* Perform color deconvolution
* Detect the holes in the tissue contour with a pixel classifier
* In each ROI, detect nuclei and DAB cells with Stardist
* Find out the number of DAB cells colocalizing with a nucleus

### Dependencies

* **QuPath pixel classifier* named *DAB*
* **Stardist** QuPath extension + *he_heavy_augment.pb* and *dsb2018_heavy_augment.pb* models

### Version history

Version 1 released on July 7, 2022.

