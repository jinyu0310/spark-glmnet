package org.apache.spark.ml.tuning

import org.apache.spark.ml.param.{ ParamMap, IntParam, ParamValidators }

class AutoGeneratedParamGridBuilder extends ParamGridBuilder {

  //usage: buildWithAutoGeneratedGrid("lambdaIndex", $(lr.maxIterParam))
  def buildWithAutoGeneratedGrid(name: String, numAutoGeneratedGridPoints: Int): Array[ParamMap] = {
    val proto = new IntParam("", name, "autogenerated parameter", ParamValidators.gtEq(0))

    build().flatMap { pm =>
      (0 until numAutoGeneratedGridPoints).map(index => pm.copy.put(proto.w(index)))
    }
  }
}