package colorinference

/**
 * Created with IntelliJ IDEA.
 * User: sharon
 * Date: 11/2/12
 * Time: 12:32 AM
 * To change this template use File | Settings | File Templates.
 */


import cc.factorie.{SamplingMaximizer, VariableSettingsSampler}
import scala.collection.mutable._
import java.io.File
import cc.factorie._
import la.Tensor1
import scala.util.Random
import cc.factorie.DiffList


object ModelTraining
{
    def apply(trainingMeshes:Array[SegmentMesh]) : ColorInferenceModel =
    {
        val training = new ModelTraining
        training.train(trainingMeshes)
    }
}

// This does not use labels
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

    /* Quantizers */
    private val uniformQuant10 = new UniformVectorQuantizer(Array(10))

    /* Unary color properties */
    val unary = new ArrayBuffer[UnarySegmentProperty]()
    unary += UnarySegmentProperty("Lightness", (c:Color) => { Tensor1(c.copyIfNeededTo(LABColorSpace)(0)) }, uniformQuant10)
    unary += UnarySegmentProperty("Saturation", (c:Color) => { Tensor1(c.copyIfNeededTo(HSVColorSpace)(1)) }, uniformQuant10)

    /* Binary color properties */
    // The assumption for the binary properties thus far is that they're symmetric (no directionality between the variables), which is probably ok
    val binary = new ArrayBuffer[BinarySegmentProperty]()
    binary += BinarySegmentProperty("Contrast", (c1:Color, c2:Color) => { Tensor1(Color.contrast(c1, c2)) }, uniformQuant10)
    binary += BinarySegmentProperty("Hue Complementarity", (c1:Color, c2:Color) => { Tensor1(Color.hueComplementarity(c1, c2)) }, uniformQuant10)
    binary += BinarySegmentProperty("Relative Saturation", (c1:Color, c2:Color) => { Tensor1(Color.relativeSaturation(c1, c2)) }, uniformQuant10)

    def train(trainingMeshes:Array[SegmentMesh]) : ColorInferenceModel =
    {
        /** Extract training data points from meshes **/

        // Training meshes with more segments generate more samples. Here we eliminate that bias
        // repeating the examples according to the lcm doesn't work...as the lcm turns out to be big, and we run out of heap space
        // so we'll weight each example according to 1/numSegments or 1/numAdjacencies. Scale by 2, so we don't run into rounding errors (when Weka checks that weights add up to >=1)
        for (mesh <- trainingMeshes)
        {
            val unaryWeight = 2.0/mesh.segments.length

            // Unary stuff
            for (seg <- mesh.segments)
            {
                val fvec = Segment.getUnaryRegressionFeatures(seg)
                for (prop <- unary) { prop.examples += HistogramRegressor.RegressionExample(prop.extractor(seg.group.color.observedColor), fvec, unaryWeight) }
            }

            var checkAdj = 0
            for (seg1<-mesh.segments; seg2 <- seg1.adjacencies if seg1.index < seg2.index)
                checkAdj+=1

            val binaryWeight =  2.0/checkAdj

            // Binary stuff
            for (seg1 <- mesh.segments; seg2 <- seg1.adjacencies if seg1.index < seg2.index)
            {
                val fvec = Segment.getBinaryRegressionFeatures(seg1, seg2)
                for (prop <- binary) { prop.examples += HistogramRegressor.RegressionExample(prop.extractor(seg1.group.color.observedColor,seg2.group.color.observedColor), fvec, binaryWeight) }
            }
        }

        /** Construct model **/
        val model = new ColorInferenceModel
        for (i <- 0 until unary.length)
        {
            val template = new DiscreteUnarySegmentTemplate(unary(i))
            template.setWeight(1.0)
            model += template
        }
        for (i <- 0 until binary.length)
        {
            val template = new DiscreteBinarySegmentTemplate(binary(i))
            template.setWeight(1.0)
            model += template
        }

        /** Train weights of the model **/
        // TODO: Remove the 'setWeight' calls above and actually do parameter estimation

        model
    }
}

//Note: A lower score is better
object PatternMain {
  //Just a place to test loading and evaluating patterns/images. Subject to much change
  val inputDir = "../PatternColorizer/out/mesh"
  val outputDir = "../PatternColorizer/out/specs"

  var meshes:ArrayBuffer[SegmentMesh] = new ArrayBuffer[SegmentMesh]()
  var files:Array[File] = null
  val random = new Random()
  val numIterations = 100

  def main(args:Array[String])
  {
      // Verify that outputDir exists
      val outputDirTestFile = new File(outputDir)
      if (!outputDirTestFile.exists)
          outputDirTestFile.mkdir

    //load all files
    files = new File(inputDir).listFiles.filter(_.getName.endsWith(".txt"))

    if (files.length == 0)
      println("No files found in the input directory!")

    for (f <- files)
    {
      meshes.append(new SegmentMesh(DiscreteColorVariable, f.getAbsolutePath))
    }

    var avgTScore:Double = 0
    var randTScore:Double = 0
    var tcount = 0
    //test the model by training and testing on the same mesh
    /*for (idx<-meshes.indices if idx<5)
    {
      println("Testing model on mesh " + files(idx).getName )
      val (score, rand) = TrainTestModel(meshes(idx), Array[SegmentMesh]{meshes(idx)})
      avgTScore += score
      randTScore += rand
      tcount +=1
    }*/
    avgTScore /= tcount
    randTScore /= tcount

    //for now, just try using the original palette
    var avgScore:Double = 0
    var count = 0
    var randScore:Double = 0

    for (idx <- meshes.indices if idx < 10)
    {
      println("Testing mesh " + idx)
      val segmesh = meshes(idx)
      count += 1
      //get all the other meshes
      val trainingMeshes:Array[SegmentMesh] = {for (tidx<-meshes.indices if tidx != idx) yield meshes(tidx)}.toArray

      // Evaluate assignments
      val (score, rscore) = TrainTestModel(segmesh, trainingMeshes)
      avgScore += score
      randScore += rscore

      // Output the result
      segmesh.saveColorAssignments(outputDir+"/"+files(idx).getName)

    }

    println("Hold-one-out results")
    println("Average Score: " + (avgScore/count))
    println("Average Random Score: " + (randScore/count))

    println("\nWhen training and testing on the same mesh..")
    println("Average Score: " + avgTScore)
    println("Average Random Score: " + randTScore)

  }

  def RandomAssignment(segmesh:SegmentMesh, palette:ColorPalette) : Seq[Color] =
  {
     segmesh.groups.map(g => palette(random.nextInt(palette.length)))
  }


  def TrainTestModel(segmesh:SegmentMesh, trainingMeshes:Array[SegmentMesh]):(Double,Double) =
  {
      // set the variable domain
      val palette = ColorPalette(segmesh)
      DiscreteColorVariable.initDomain(palette)

    val model = ModelTraining(trainingMeshes)
    model.conditionOn(segmesh)

    // Do inference
    println("Performing inference")
    /*val sampler = new VariableSettingsSampler[DiscreteColorVariable](model)
    val optimizer = new SamplingMaximizer(sampler)
    optimizer.maximize(for (group <- segmesh.groups) yield group.color.asInstanceOf[DiscreteColorVariable], numIterations)*/

    ExhaustiveSearch.allCombinations(segmesh, model)

    //ExhaustiveSearch.allPermutations(segmesh, model)

    // Evaluate assignments
    val score = segmesh.scoreAssignment()
    println("Score: "+score)

    //Evaluate random assignment (3 trials)
    var rscore = 0.0
    for (t <- 0 until 3)
    {
      val assign = RandomAssignment(segmesh, palette)
      rscore += segmesh.scoreAssignment(assign)
    }
    rscore /= 3.0
    println("Random score: " + rscore)

    (score,rscore)

  }

}
//TODO: incorporate this more nicely with  Factorie?
//There are some ugly type casts here...hopefully some way to fix
object ExhaustiveSearch
{

  //from: http://stackoverflow.com/questions/1070859/listing-combinations-with-repetitions-in-scala
  def mycomb[T](n: Int, l: List[T]): List[List[T]] =
    n match {
      case 0 => List(List())
      case _ => for(el <- l;
                    sl <- mycomb(n-1, l dropWhile { _ != el } ))
      yield el :: sl
    }

  def comb[T](n: Int, l: List[T]): List[List[T]] = mycomb(n, l.distinct)

  def allCombinations(mesh:SegmentMesh, model:Model)
  {
    println("Starting exhaustive search through all combos")

    val numVals = DiscreteColorVariable.domain.size
    val vars = mesh.groups.map(g => g.color)
    val allCombs = comb[Int](vars.size, (0 until numVals).toList)
    var iters = 0

    for (c <- allCombs; p <- c.permutations)
      iters += 1

    println("Number of combinations: "+iters)

    var bestScore = Double.NegativeInfinity
    var currIter = 0
    val itemizedModel = model.itemizedModel(vars)
    for (c <- allCombs; p<-c.permutations)
    {
      if (currIter % 500 == 0) println("Current iteration ..." + currIter)
      currIter += 1
      //create the new assignment
      //TODO: learn DiffLists
      val assignment = new HashMapAssignment(vars)
      for (i <- mesh.groups.indices)
      {
        assignment.update(mesh.groups(i).color.asInstanceOf[DiscreteColorVariable], DiscreteColorVariable.domain(p(i)))
      }
      //TODO: this is ugly
      for (f <- itemizedModel.factors)
      {
         f.variables.foreach{ e => e match {
           case(v:UnarySegmentTemplate.DatumVariable) => assignment.update(v, v.value)
           case(b:BinarySegmentTemplate.DatumVariable) => assignment.update(b, b.value)
           case _ => null
         }}
      }


      val currScore = model.assignmentScore(vars, assignment)
      if (currScore > bestScore)
      {
          //set the assignment
          for (i <- mesh.groups.indices)
          {
            mesh.groups(i).color.setColor(DiscreteColorVariable.domain.category(p(i)))
          }
          bestScore = currScore
      }

    }

  }


  def allPermutations(mesh:SegmentMesh, model:Model)
  {

    println("Starting exhaustive search through all permutation")
     val numVals = DiscreteColorVariable.domain.size
     val vars = mesh.groups.map(g => g.color)
      assert(numVals==vars.size, "allPermutations: Number of variables is not equal to domain!")

     val allPerms = (0 until numVals).toList.permutations.toList

     println("Number of permutations " + allPerms.length)
     var currIter = 0
     var bestScore = Double.NegativeInfinity

    val itemizedModel = model.itemizedModel(vars)

    for (p <- allPerms)
    {
      if (currIter % 10 == 0) println("Current iteration ..." + currIter)
      currIter += 1
      //create the new assignment
      val assignment = new HashMapAssignment(vars)
      for (i <- mesh.groups.indices)
      {
        assignment.update(mesh.groups(i).color.asInstanceOf[DiscreteColorVariable], DiscreteColorVariable.domain(p(i)))
      }

      for (f <- itemizedModel.factors)
      {
        f.variables.foreach{ e => e match {
          case(v:UnarySegmentTemplate.DatumVariable) => assignment.update(v, v.value)
          case(b:BinarySegmentTemplate.DatumVariable) => assignment.update(b, b.value)
          case _ => null
        }}
      }


      val currScore = model.assignmentScore(vars, assignment)
      if (currScore > bestScore)
      {
        //set the assignment
        for (i <- mesh.groups.indices)
        {
          mesh.groups(i).color.setColor(DiscreteColorVariable.domain.category(p(i)))
        }
        bestScore = currScore

      }

    }

  }

}
