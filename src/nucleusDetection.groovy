import org.apache.commons.io.FilenameUtils
import qupath.ext.stardist.StarDist2D
import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.images.servers.ColorTransforms
import qupath.lib.objects.classes.PathClassFactory
import static qupath.lib.scripting.QP.*


// Init project
setImageType('BRIGHTFIELD_H_DAB')
def project = getProject()
def pathProject = buildFilePath(PROJECT_BASE_DIR)
def pathModelNuclei = buildFilePath(pathProject, 'models', 'he_heavy_augment.pb')
if (pathModelNuclei == null) {
    Dialogs.showErrorMessage('Problem', 'No H&E StarDist model found')
    return
}
def pathModelDAB = buildFilePath(pathProject, 'models', 'dsb2018_heavy_augment.pb')
if (pathModelDAB == null) {
    Dialogs.showErrorMessage('Problem', 'No DSB StarDist model found')
    return
}
def imageDir = new File(project.getImageList()[0].getUris()[0]).getParent()

// Create results file and write headers
def resultsDir = buildFilePath(imageDir, '/../Results')
if (!fileExists(resultsDir)) mkdirs(resultsDir)
def resultsFile = new File(buildFilePath(resultsDir, 'Results.csv'))
resultsFile.createNewFile()
def res = "Image name\tAnimal number\tMutant\tTreatment\tTime\tAnnotation\tAnnotation area (um2)\tDAB region area\t" +
        "Nb nuclei\tNb DAB cells\tNb DAB cells-nuclei\n"
resultsFile.write(res)

// Define ClassPaths
def DABRoiClass = PathClassFactory.getPathClass("DAB", makeRGB(0, 255, 0))
def NucleiClass = PathClassFactory.getPathClass("Nucleus", makeRGB(0, 0, 255))
def DABCellsClass = PathClassFactory.getPathClass("DAB Cell", makeRGB(255, 165, 0))
def DABNucleiClass = PathClassFactory.getPathClass("DAB+Nucleus Cell", makeRGB(255, 0, 0))

def stardistNuclei = StarDist2D.builder(pathModelNuclei)
        .threshold(0.4)            // Prediction threshold
        .normalizePercentiles(1, 99)       // Percentile normalization
        .pixelSize(0.5)              // Resolution for detection
        .measureShape()
        .measureIntensity()
        .classify(NucleiClass)
        .build()

def stardistDAB = StarDist2D.builder(pathModelDAB)
        .threshold(0.6)              // Prediction threshold
        .normalizePercentiles(60, 99)         // Percentile normalization
        .pixelSize(0.5)              // Resolution for detection
        .channels(ColorTransforms.createColorDeconvolvedChannel(getCurrentImageData().getColorDeconvolutionStains(), 2))
        .measureShape()
        .measureIntensity()
        .classify(DABCellsClass)
        .build()

// Find  DAB cells to nuclei centroid distances
// If distance < 10, classify as dabNuc cell
def classifyCells(dabCells, nuclei, cellClass) {
    def nbCells = 0
    for (nucleus in nuclei) {
        def nucleusROI = nucleus.getROI()
        for (cell in dabCells) {
            def cellROI = cell.getROI()
            if (nucleusROI.contains(cellROI.getCentroidX(), cellROI.getCentroidY())) {
                cell.setPathClass(cellClass)
                nbCells++
            }
        }
    }
    return nbCells
}

// Save annotations
def saveAnnotations(imgName) {
    def path = buildFilePath(imgName + '.annot')
    def annotations = getAnnotationObjects()
    new File(path).withObjectOutputStream {
        it.writeObject(annotations)
    }
    println('Annotations saved')
}

// Loop over images in project
for (entry in project.getImageList()) {
    def imageData = entry.readImageData()
    def server = imageData.getServer()
    def cal = server.getPixelCalibration()
    def pixelWidth = cal.getPixelWidth().doubleValue()
    def pixelUnit = cal.getPixelWidthUnit()
    def imgName = entry.getImageName()
    def imgNameWithOutExt = FilenameUtils.removeExtension(imgName)
    def name = imgName.split('-')
    def animalNb = name[0]
    def mutant = name[1]
    def treatment = name[2]
    def time = name[3]
    setBatchProjectAndImage(project, imageData)
    println ''
    println '------ ANALYZING IMAGE ' + imgName + ' ------'

    // Find annotations
    def allAnnotations = getAnnotationObjects()
    def annotations = allAnnotations.findAll{ it.getName() != 'background'}
    if (annotations.isEmpty()) {
        Dialogs.showErrorMessage("Problem", "Please create ROIs to analyze in image " + imgName)
        continue
    }
    def index = annotations.size()
    for (an in annotations) {
        if (an.getName() == null) {
            index++
            an.setName("ROI_" + index)
        }
    }
    def background = allAnnotations.findAll{it.getName() == 'background'}[0]
    if (background == null) {
        Dialogs.showErrorMessage("Problem", "Please provide a background ROI for image " + imgName)
        break
    }
    selectObjects(background)
    runPlugin('qupath.lib.algorithms.IntensityFeaturesPlugin', '{"pixelSizeMicrons": 2.0,  "region": "ROI",  "tileSizeMicrons": 25.0,  "colorOD": false,  ' +
            '"colorStain1": true,  "colorStain2": true,  "colorStain3": false,  "colorRed": false,  "colorGreen": false,  "colorBlue": false,  "colorHue": false,  "colorSaturation": false,  ' +
            '"colorBrightness": false,  "doMean": true,  "doStdDev": false,  "doMinMax": false,  "doMedian": false,  "doHaralick": false}')
    def bgIntHematoxylin = background.getMeasurementList().getMeasurementValue('ROI: 2.00 µm per pixel: Hematoxylin: Mean')
    def bgIntDAB = background.getMeasurementList().getMeasurementValue('ROI: 2.00 µm per pixel: DAB: Mean')
    println 'Background median intensity in Hematoxylin channel = ' + bgIntHematoxylin
    println 'Background median intensity in DAB channel = ' + bgIntDAB

    println '-Finding DAB region in each ROI...'
    def classifier = project.getPixelClassifiers().get('DAB')
    setColorDeconvolutionStains('{"Name" : "H-DAB default", "Stain 1" : "Hematoxylin", "Values 1" : "0.65111 0.70119 0.29049", "Stain 2" : "DAB", "Values 2" : "0.26917 0.56824 0.77759", "Background" : " 255 255 255"}');
    deselectAll()
    selectObjects(annotations)
    createAnnotationsFromPixelClassifier(classifier, 750.0, 750.0, 'DELETE_EXISTING')
    def DABRois = getAnnotationObjects().findAll({it.getPathClass() == DABRoiClass})

    for (roi in DABRois) {
        def an = roi.getParent()
        println '--- Analyzing ROI ' + an.getName() + ' of image ' + imgName + ' ---'

        // Find annotation area
        def regionArea = an.getROI().getScaledArea(pixelWidth, pixelWidth)
        println an.getName() + ' ROI area = ' + regionArea + ' ' + pixelUnit + '2'

        // Find DAB ROI and its area
        def DABArea = roi.getROI().getScaledArea(pixelWidth, pixelWidth)
        println 'DAB region area = ' + DABArea + ' ' + pixelUnit + '2'

        // Find nuclei in DAB region
        println '-Finding nuclei in DAB region...'
        stardistNuclei.detectObjects(imageData, roi, true)
        def nuclei = roi.getChildObjects().findAll{it.getPathClass() == NucleiClass
                && it.getMeasurementList().getMeasurementValue('Area µm^2') < 250
                && it.getMeasurementList().getMeasurementValue('Hematoxylin: Mean') > (1.5 * bgIntHematoxylin)}
        println 'Nb of nuclei detected = ' + nuclei.size()

        // Find DAB cells in DAB region
        println '-Finding DAB cells in DAB region ...'
        stardistDAB.detectObjects(imageData, roi, true)
        def DABCells = roi.getChildObjects().findAll{it.getPathClass() == DABCellsClass
                && it.getMeasurementList().getMeasurementValue('Area µm^2') < 150
                && it.getMeasurementList().getMeasurementValue('DAB: Mean') > (1.5 * bgIntDAB)}
        println 'Nb of DAB cells detected = ' + DABCells.size()

        def nb_DABCells_nuclei = classifyCells(DABCells, nuclei, DABNucleiClass)
        println 'Nb of DAB cells colocalizing with a nucleus  = ' + nb_DABCells_nuclei

        roi.clearPathObjects()
        roi.addPathObjects(nuclei)
        roi.addPathObjects(DABCells)

        deselectAll()
        selectObjects(an)
        runPlugin('qupath.lib.plugins.objects.DilateAnnotationPlugin', '{"radiusMicrons": 0.5,  "lineCap": "Round",  "removeInterior": false,  "constrainToParent": true}');
        def newAn = getAnnotationObjects().last()
        newAn.addPathObject(roi)
        deselectAll()
        selectObjects(an)
        clearSelectedObjects()

        // Save annotations in Shapes format
        clearAllObjects()
        addObject(newAn)
        addObject(roi)
        saveAnnotations(buildFilePath(resultsDir, imgNameWithOutExt+"_"+newAn.getName()))

        // Write results
        def results = imgNameWithOutExt + "\t" + animalNb + "\t" + mutant + "\t" + treatment + "\t" + time + "\t" + newAn.getName() + "\t" + regionArea + "\t" + DABArea + "\t" +
                nuclei.size() + "\t" + DABCells.size() + "\t" + nb_DABCells_nuclei + "\n"
        resultsFile << results
    }
    clearAllObjects()
    addObject(background)
    saveAnnotations(buildFilePath(resultsDir, imgNameWithOutExt+"_"+background.getName()))
}