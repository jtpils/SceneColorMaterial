package colorinference

/**
 * Created with IntelliJ IDEA.
 * User: Daniel
 * Date: 11/9/12
 * Time: 1:36 PM
 * To change this template use File | Settings | File Templates.
 */

import cc.factorie._
import la.Tensor1

/**
 * Factor that enforces that the luminanceContrast between two colors should be similar to the observed luminanceContrast between
 * their owner groups
 */
class PairwiseMaintainObservedContrastFactor(v1:DiscreteColorVariable, v2:DiscreteColorVariable) extends Factor2(v1,v2)
{
    private val sigma = 0.2     // I just made this up
    private val targetContrast = Color.luminanceContrast(v1.observedColor, v2.observedColor)

    def score(val1:DiscreteColorVariable#Value, val2:DiscreteColorVariable#Value) =
    {
        val contrast = Color.luminanceContrast(val1.category, val2.category)
        // This is intended to be a gaussian, but we don't exponentiate it because factorie operates in log space
        -math.abs(contrast - targetContrast) / sigma
    }
}

class TargetSaturationFactor(v:DiscreteColorVariable, private val target:Double, private val bandwidth:Double) extends Factor1(v)
{
    def score(v:DiscreteColorVariable#Value) =
    {
        val saturation = v.category.copyIfNeededTo(HSVColorSpace)(1)
        MathUtils.logGaussianKernel(saturation, target, bandwidth)
    }
}

class TargetValueFactor(v:DiscreteColorVariable, private val target:Double, private val bandwidth:Double) extends Factor1(v)
{
    def score(v:DiscreteColorVariable#Value) =
    {
        val value = v.category.copyIfNeededTo(HSVColorSpace)(2)
        MathUtils.logGaussianKernel(value, target, bandwidth)
    }
}

class TargetContrastFactor(v1:DiscreteColorVariable, v2:DiscreteColorVariable, private val target:Double, private val bandwidth:Double) extends Factor2(v1, v2)
{
    def score(val1:DiscreteColorVariable#Value, val2:DiscreteColorVariable#Value) =
    {
        val contrast = Color.luminanceContrast(val1.category, val2.category)
        MathUtils.logGaussianKernel(contrast, target, bandwidth)
    }
}

class TargetComplementarityFactor(v1:DiscreteColorVariable, v2:DiscreteColorVariable, private val target:Double, private val bandwidth:Double) extends Factor2(v1,v2)
{
    def score(val1:DiscreteColorVariable#Value, val2:DiscreteColorVariable#Value) =
    {
        val complementarity = Color.hueAngle(val1.category, val2.category)
        MathUtils.logGaussianKernel(complementarity, target, bandwidth)
    }
}