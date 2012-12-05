/**
 * Created with IntelliJ IDEA.
 * User: Daniel
 * Date: 11/15/12
 * Time: 11:22 AM
 * To change this template use File | Settings | File Templates.
 */

import cc.factorie.la.Tensor1
import jnisvmlight._

class ConditionalHistogramRegressor(examples:Seq[ConditionalHistogramRegressor.RegressionExample], private val metric:MathUtils.DistanceMetric, quantizer:VectorQuantizer)
{
    private var centroids:IndexedSeq[Tensor1] = null
    private var regressors:Array[SVMLightModel] = null
    private var featureIndices:Array[Int] = null

    train(examples, quantizer)

    private def train(examples:Seq[ConditionalHistogramRegressor.RegressionExample], quantizer:VectorQuantizer)
    {
        assert(examples.length > 0, {println("ConditionalHistogramRegressor: cannot train with 0 training examples")})
        featureIndices = (0 until examples(0).features.length).toArray

        // Invoke the quantizer
        println("Quantizing...")
        val (centroids, assignments) = quantizer(for (ex <- examples) yield ex.target, metric)
        this.centroids = centroids
        val numBins = centroids.length

        // Set up the JNI interface to svmlight
        val trainer = new SVMLightInterface()
        //SVMLightInterface.SORT_INPUT_VECTORS = false    // Dimensions will already be sorted

        // Set up necessary training parameters
        val params = new TrainingParameters()
        //TODO: Changing this to Regression seems to make things very slow. Might be a parameter or training data issue?
        params.getLearningParameters.`type` = LearnParam.REGRESSION.toLong
        //params.getLearningParameters.svm_c = 1.0
        // TODO: Consider non-default (linear) kernels, such as RBF kernel?
        //params.getLearningParameters.verbosity = 1

        // Train 'em up!
        val trainingHist = new VectorHistogram(metric)
        trainingHist.setData(centroids, for (i <- 0 until centroids.length) yield 0.0)
        regressors = new Array[SVMLightModel](numBins)
        for (i <- 0 until numBins)
        {
            println("ConditionalHistogramRegressor: Training bin " + i)
            // Fill in training data in the format expected by svmlight
            println("Formatting training data...")
            val trainingData = new Array[LabeledFeatureVector](examples.length)
            for (j <- 0 until examples.length)
            {
                val ex = examples(j)
                //val target = if (assignments(j) == i) 1.0 else 0.0
                val target = trainingHist.evaluateBinAt(i, ex.target)
                val dims = (0 until ex.features.length).map(x => x+1).toArray  //feature numbers have to be >=1
                val features = ex.features.toArray
                trainingData(j) = new LabeledFeatureVector(target, dims, features)
            }
            // TODO: Consider normalizing/standardizing the data so that linear kernel turns into cosine/mahalanobis distance?

            // Finally, train the SVM regressor
            println("Training SVM regressor...")
            regressors(i) = trainer.trainModel(trainingData, params)
        }
    }

    def predictHistogram(featureVec:Tensor1) : VectorHistogram =
    {
        val svmlightfeatures = new FeatureVector(featureIndices, featureVec.toArray)
        val bins = new Array[Double](centroids.length)
        var totalmass = 0.0
        for (i <- 0 until bins.length)
        {
            val prediction = MathUtils.clamp(regressors(i).classify(svmlightfeatures), 0.0, 1.0)
            bins(i) = prediction
            totalmass += prediction
        }

        // Normalize bins
        for (i <- 0 until bins.length) bins(i) /= totalmass

        val hist = new VectorHistogram(metric)
        hist.setData(centroids, bins)
        hist
    }
}

object ConditionalHistogramRegressor
{
    case class RegressionExample(target:Tensor1, features:Tensor1) {}
}
