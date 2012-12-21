package colorinference

import cc.factorie.la.Tensor1
import collection.mutable.ArrayBuffer
import cc.factorie.{SampleRankExample, SampleRankTrainer, GibbsSampler, SampleRank, VariableSettingsSampler}
import cc.factorie.optimize.StepwiseGradientAscent

/**
 * Created with IntelliJ IDEA.
 * User: Daniel
 * Date: 12/18/12
 * Time: 11:26 AM
 * To change this template use File | Settings | File Templates.
 */

object ModelTraining
{
    val namingModel = new ColorNamingModel("../c3_data.json")

    /* Color properties */

    // Unary
    def colorfulness(c:Color) = Tensor1(c.colorfulness)
    def lightness(c:Color) = Tensor1(c.copyIfNeededTo(LABColorSpace)(0))
    def nameSaliency(c:Color) = Tensor1(namingModel.saliency(c))
    //    def saturation(c:Color) = Tensor1(c.copyIfNeededTo(HSVColorSpace)(1))

    // Binary
    def perceptualDifference(c1:Color, c2:Color) = Tensor1(Color.perceptualDifference(c1, c2))
    def chromaDifference(c1:Color, c2:Color) = Tensor1(Color.chromaDifference(c1, c2))
    def relativeColorfulness(c1:Color, c2:Color) = Tensor1(Color.relativeColorfulness(c1, c2))
    def relativeLightness(c1:Color, c2:Color) = Tensor1(Color.relativeLightness(c1, c2))
    def nameSimilarity(c1:Color, c2:Color) = Tensor1(namingModel.cosineSimilarity(c1, c2))
    //    def luminanceContrast(c1:Color, c2:Color) = Tensor1(Color.luminanceContrast(c1, c2))
    //    def hueComplementarity(c1:Color, c2:Color) = Tensor1(Color.hueAngle(c1, c2))
    //    def relativeSaturation(c1:Color, c2:Color) = Tensor1(Color.relativeSaturation(c1, c2))


    /* Quantizers */
    val uniformQuant10 = new UniformVectorQuantizer(Array(10))

    def apply(trainingMeshes:Array[SegmentMesh]) : ColorInferenceModel =
    {
        val training = new ModelTraining
        training.train(trainingMeshes)
    }
}

class ModelTraining
{
    type Examples = ArrayBuffer[HistogramRegressor.RegressionExample]
    case class UnarySegmentProperty(name:String, extractor:UnarySegmentTemplate.ColorPropertyExtractor, quant:VectorQuantizer)
    {
        val examples = new Examples
    }
    case class BinarySegmentProperty(name:String, extractor:BinarySegmentTemplate.ColorPropertyExtractor, quant:VectorQuantizer)
    {
        val examples = new Examples
    }
    case class ColorGroupProperty(name:String, extractor:ColorGroupTemplate.ColorPropertyExtractor, quant:VectorQuantizer)
    {
        val examples = new Examples
    }

    /* Unary segment properties */
    val unarySegProps = new ArrayBuffer[UnarySegmentProperty]()
    unarySegProps += UnarySegmentProperty("Lightness", ModelTraining.lightness, ModelTraining.uniformQuant10)
    unarySegProps += UnarySegmentProperty("Colorfulness", ModelTraining.colorfulness, ModelTraining.uniformQuant10)
    unarySegProps += UnarySegmentProperty("Name Saliency", ModelTraining.nameSaliency, ModelTraining.uniformQuant10)
    //    unarySegProps += UnarySegmentProperty("Saturation", ModelTraining.saturation, ModelTraining.uniformQuant10)

    /* Binary segment properties */
    // The assumption for the binary properties thus far is that they're symmetric (no directionality between the variables), which is probably ok
    val binarySegProps = new ArrayBuffer[BinarySegmentProperty]()
    binarySegProps += BinarySegmentProperty("Perceptual Difference", ModelTraining.perceptualDifference, ModelTraining.uniformQuant10)
    binarySegProps += BinarySegmentProperty("Chroma Difference", ModelTraining.chromaDifference, ModelTraining.uniformQuant10)
    binarySegProps += BinarySegmentProperty("Relative Colorfulness", ModelTraining.relativeColorfulness, ModelTraining.uniformQuant10)
    binarySegProps += BinarySegmentProperty("Relative Lightness", ModelTraining.relativeLightness, ModelTraining.uniformQuant10)
    binarySegProps += BinarySegmentProperty("Name Similarity", ModelTraining.nameSimilarity, ModelTraining.uniformQuant10)
    //    binarySegProps += BinarySegmentProperty("Luminance Contrast", ModelTraining.luminanceContrast, ModelTraining.uniformQuant10)
    //    binarySegProps += BinarySegmentProperty("Hue Complementarity", ModelTraining.hueComplementarity, ModelTraining.uniformQuant10)
    //    binarySegProps += BinarySegmentProperty("Relative Saturation", ModelTraining.relativeSaturation, ModelTraining.uniformQuant10)

    /* Color group properties */
    val groupProps = new ArrayBuffer[ColorGroupProperty]()
    groupProps += ColorGroupProperty("Lightness", ModelTraining.lightness, ModelTraining.uniformQuant10)
    groupProps += ColorGroupProperty("Colorfulness", ModelTraining.colorfulness, ModelTraining.uniformQuant10)
    groupProps += ColorGroupProperty("Name Saliency", ModelTraining.nameSaliency, ModelTraining.uniformQuant10)

    def train(trainingMeshes:Array[SegmentMesh]) : ColorInferenceModel =
    {
        /** Extract training data points from meshes **/

        // Training meshes with more segments generate more samples. Here we eliminate that bias
        // repeating the examples according to the lcm doesn't work...as the lcm turns out to be big, and we run out of heap space
        // so we'll weight each example according to 1/numSegments or 1/numAdjacencies. Scale by 2, so we don't run into rounding errors (when Weka checks that weights add up to >=1)
        for (mesh <- trainingMeshes)
        {
            val unaryWeight = 2.0/mesh.segments.length

            // Unary segment properties
            for (seg <- mesh.segments)
            {
                val fvec = Segment.getUnaryRegressionFeatures(seg)
                for (prop <- unarySegProps) { prop.examples += HistogramRegressor.RegressionExample(prop.extractor(seg.group.color.observedColor), fvec, unaryWeight) }
            }

            var checkAdj = 0
            for (seg1<-mesh.segments; seg2 <- seg1.adjacencies if seg1.index < seg2.index)
                checkAdj+=1

            val binaryWeight =  2.0/checkAdj

            // Binary segment properties
            for (seg1 <- mesh.segments; seg2 <- seg1.adjacencies if seg1.index < seg2.index)
            {
                val fvec = Segment.getBinaryRegressionFeatures(seg1, seg2)
                for (prop <- binarySegProps) { prop.examples += HistogramRegressor.RegressionExample(prop.extractor(seg1.group.color.observedColor,seg2.group.color.observedColor), fvec, binaryWeight) }
            }

            // Group properties
            // TODO: Should these training examples be weighted like the ones above? I think it's probably unnecessary.
            for (group <- mesh.groups)
            {
                val fvec = SegmentGroup.getRegressionFeatures(group)
                for (prop <- groupProps) { prop.examples += HistogramRegressor.RegressionExample(prop.extractor(group.color.observedColor), fvec)}
            }
        }

        /** Construct model **/
        val model = new ColorInferenceModel
        for (i <- 0 until unarySegProps.length)
        {
            val template = new DiscreteUnarySegmentTemplate(unarySegProps(i))
            template.setWeight(1.0)
            model += template
        }
        for (i <- 0 until binarySegProps.length)
        {
            val template = new DiscreteBinarySegmentTemplate(binarySegProps(i))
            template.setWeight(1.0)
            model += template
        }
        for (i <- 0 until groupProps.length)
        {
            val template = new DiscreteColorGroupTemplate(groupProps(i))
            template.setWeight(1.0)
            model += template
        }

        /** Train weights of the model **/
        // TODO: Remove the 'setWeight' calls above and actually do parameter estimation
        println("Tuning weights...")
        val objective = new AssignmentScoreTemplate()
        val trainer = new SampleRank(new VariableSettingsSampler[DiscreteColorVariable](model, objective), new StepwiseGradientAscent)

      //go through all training meshes
      //we iterate through each mesh in the inner loop, so as to not bias the tuning towards later meshes

        val iterations = 10

      //pre-condition the model and objective on all the meshes
      //as we increase the number of training meshes, this may or may not be feasible, and we may have to condition the model
      //on each inner loop iteration
      //TODO: try other weight trainers, or turn off and on different features?
      //TODO: weights don't really seem to converge much after the first iteration. we may need a different scoring objective, or add more constraints

      model.conditionOnAll(trainingMeshes)
      objective.conditionOnAll(trainingMeshes)

      var prevWeights:Tensor1 = MathUtils.concatVectors({for (t<-model.templates) yield t match {case c:ColorInferenceModelComponent => c.weights}})
      for (i <- 0 until iterations)
      {
        var avgAccuracy = 0.0
        for (mesh <- trainingMeshes)
        {
          //set the pattern domain
          val palette = ColorPalette(mesh)
          DiscreteColorVariable.initDomain(palette)

          // Convert colors to LAB space, since most of our factors use LAB features
          for (color <- palette) color.convertTo(LABColorSpace)


          //model.conditionOn(mesh)
          //objective.conditionOn(mesh)

          //process the variables and learn the weights
            val vars = mesh.groups.map(g => g.color.asInstanceOf[DiscreteColorVariable])

            trainer.processAll(vars)

            //print the accuracy
            //println("Iteration "+i+" Training Accuracy: " + objective.accuracy(vars))

            avgAccuracy += objective.accuracy(vars)
            print(".")
          }

          //print change in weights
          val curWeights:Tensor1 = MathUtils.concatVectors({for (t<-model.templates) yield t match {case c:ColorInferenceModelComponent => c.weights}})
          println("\nWeights delta: " + (curWeights-prevWeights).twoNorm)
          prevWeights = curWeights

          println("Iteration " + i+ " Overall Training Accuracy: " + avgAccuracy/trainingMeshes.length)
        }


        println("Weights:")

        //print the weights
        for (t<-model.templates)
        {
          t match
          {
            case u:UnarySegmentTemplate[DiscreteColorVariable] => println("unary " + u.propName + " " + u.weights)
            case b:BinarySegmentTemplate[DiscreteColorVariable] => println("binary " + b.propName + " " + b.weights)
            case g:ColorGroupTemplate[DiscreteColorVariable] => println("group " + g.propName + " " + g.weights)
            case _ => null
          }
        }
        println()



        model
    }


}

