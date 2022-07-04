import org.apache.commons.io.FilenameUtils
import qupath.ext.stardist.StarDist2D
import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.images.servers.ColorTransforms
import qupath.lib.objects.classes.PathClassFactory
import static qupath.lib.analysis.DistanceTools.centroidToBoundsDistance2D
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
def res = "Animal number\tMutant\tTreatment\tTime\tAnnotation\tAnnotation area (um2)\tDAB region area\tNb DAB cells\tNb nuclei\t" +
        "Nb DAB cells-nuclei\tStroma region area\tNb DAB cells\tNb nuclei\tNb DAB cells-nuclei\n"
resultsFile.write(res)

// Define ClassPaths
// Regions classpaths
def DABRoiClass = PathClassFactory.getPathClass("DAB ROI", makeRGB(0, 255, 0))
def StromaRoiClass = PathClassFactory.getPathClass("Stroma ROI", makeRGB(0, 0, 255))
// Cells classpaths
def NucleiClass = PathClassFactory.getPathClass("Nucleus", makeRGB(255, 255, 0))
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
    def hierarchy = imageData.getHierarchy()
    def annotations = getAnnotationObjects()
    if (annotations.isEmpty()) {
        Dialogs.showErrorMessage("Problem", "Please create ROIs to analyze in image " + imgName)
        continue
    }

    println('-Finding DAB and Stroma regions in each annotation...')
    def classifier = project.getPixelClassifiers().get('DAB')
    setColorDeconvolutionStains('{"Name" : "H-DAB default", "Stain 1" : "Hematoxylin", "Values 1" : "0.65111 0.70119 0.29049", "Stain 2" : "DAB", "Values 2" : "0.26917 0.56824 0.77759", "Background" : " 255 255 255"}');
    resetSelection()
    selectAnnotations()
    createAnnotationsFromPixelClassifier(classifier, 20.0, 20.0, 'DELETE_EXISTING')

    def index = 0
    for (an in annotations) {
        index++
        if (an.getName() == null) an.setName("Region_" + index)
        println '--- Analyzing ROI ' + an.getName() + ' of image ' + imgName + ' ---'

        // Get annotation area
        def regionArea = an.getROI().getScaledArea(pixelWidth, pixelWidth)
        println an.getName() + ' ROI area = ' + regionArea + ' ' + pixelUnit + '2'

        // Find DAB ROI and its area
        def DABRoi = getAnnotationObjects().findAll{! it.hasChildren()
                && it.getParent().getName() == an.getName() && it.getPathClass().getName() == 'DAB'}[0]
        def DABArea = DABRoi.getROI().getScaledArea(pixelWidth, pixelWidth)
        println 'DAB region area = ' + DABArea + ' ' + pixelUnit + '2'
        DABRoi.setPathClass(DABRoiClass)

        // Find Stroma ROI and its area
        def stromaRoi = getAnnotationObjects().findAll{! it.hasChildren()
                && it.getParent().getName() == an.getName() && it.getPathClass().getName() == 'Stroma'}[0]
        def stromaArea = stromaRoi.getROI().getScaledArea(pixelWidth, pixelWidth)
        println 'Stroma region area = ' + stromaArea + ' ' + pixelUnit + '2'
        stromaRoi.setPathClass(StromaRoiClass)

        // Find nuclei in DAB region
        println '-Finding nuclei in DAB region...'
        stardistNuclei.detectObjects(imageData, DABRoi, true)
        def NucleiInDAB = DABRoi.getChildObjects().findAll{it.getPathClass() == NucleiClass
                && it.getMeasurementList().getMeasurementValue('Area µm^2') < 150
                && it.getMeasurementList().getMeasurementValue('Hematoxylin: Mean') > 0.7}
        println 'Nb of nuclei in DAB region = ' + NucleiInDAB.size()

        // Find DAB cells in DAB region
        println '-Finding DAB cells in DAB region ...'
        stardistDAB.detectObjects(imageData, DABRoi, true)
        def DABCellsInDAB = DABRoi.getChildObjects().findAll{it.getPathClass() == DABCellsClass
                && it.getMeasurementList().getMeasurementValue('Area µm^2') < 50
                && it.getMeasurementList().getMeasurementValue('DAB: Mean') > 0.7}
        println 'Nb DAB cells in DAB region = ' + DABCellsInDAB.size()
        DABRoi.clearPathObjects()

        def nb_DABNucCells_inDAB = classifyCells(DABCellsInDAB, NucleiInDAB, DABNucleiClass)
        println 'DAB cells with nucleus in DAB region = ' + nb_DABNucCells_inDAB

        // Find nuclei in Stroma region
        println '-Finding nuclei in Stroma region...'
        stardistNuclei.detectObjects(imageData, stromaRoi, true)
        def NucleiInStroma = stromaRoi.getChildObjects().findAll{it.getPathClass() == NucleiClass
                && it.getMeasurementList().getMeasurementValue('Area µm^2') < 150
                && it.getMeasurementList().getMeasurementValue('Hematoxylin: Mean') > 0.7}
        println 'Nb of nuclei in Stroma region = ' + NucleiInStroma.size()

        // Find DAB cells in Stroma region
        println '-Finding DAB cells in Stroma region ...'
        stardistDAB.detectObjects(imageData, stromaRoi, true)
        def DABCellsInStroma = stromaRoi.getChildObjects().findAll{it.getPathClass() == DABCellsClass
                && it.getMeasurementList().getMeasurementValue('Area µm^2') < 50
                && it.getMeasurementList().getMeasurementValue('DAB: Mean') > 0.7}
        println 'Nb DAB cells in Stroma region = ' + DABCellsInStroma.size()
        stromaRoi.clearPathObjects()

        def nb_DABNucCells_inStroma = classifyCells(DABCellsInStroma, NucleiInStroma, DABNucleiClass)
        println 'DAB cells with nucleus in Stroma region = ' + nb_DABNucCells_inStroma

        // Save annotations in Shapes format
        clearAllObjects()
        addObject(an)
        addObjects(NucleiInDAB)
        addObjects(DABCellsInDAB)
        addObjects(NucleiInStroma)
        addObjects(DABCellsInStroma)
        resolveHierarchy()
        saveAnnotations(buildFilePath(resultsDir, imgNameWithOutExt+"_"+an.getName()))

        // Write results
        def results = animalNb + "\t" + mutant + "\t" + treatment + "\t" + time + "\t" + an.getName() + "\t" + regionArea + "\t" + DABArea + "\t" +
                DABCellsInDAB.size() + "\t" + NucleiInDAB.size() + "\t" + nb_DABNucCells_inDAB + "\t" + stromaArea + "\t" + DABCellsInStroma.size() +
                "\t" + NucleiInStroma.size() + "\t" + nb_DABNucCells_inStroma + "\n"
        resultsFile << results
    }
}