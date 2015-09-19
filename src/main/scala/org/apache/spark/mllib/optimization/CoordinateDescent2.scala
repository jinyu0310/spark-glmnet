/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.mllib.optimization

import java.lang.Math.abs
import scala.annotation.tailrec
import scala.collection.mutable.MutableList
import scala.math.exp
import org.apache.spark.Logging
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.mllib.linalg.{ Vector, Vectors }
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import breeze.linalg.{ Vector => BV }
import nonsubmit.utils.Timer
import org.apache.spark.mllib.linalg.mlmatrix.ToBeNamed
import org.apache.spark.mllib.linalg.DenseMatrix
import org.apache.spark.mllib.linalg.BLAS
import org.apache.spark.mllib.linalg.Matrices
import breeze.linalg.{ DenseMatrix => BDM }
import org.apache.spark.mllib.optimization.BreezeUtil.{ toBreeze, fromBreeze }

/**
 * Class used to solve an optimization problem using Coordinate Descent.
 */
//TODO - Version Two of CD that will provide a BLAS Level 3 computation of the XCorrelation
//computing XCorrelation originally took 97% of CD time for large number of rows and 45% of CD time for a large number of columns
class CoordinateDescent2 private[spark] extends CDOptimizer
  with Logging {

  private var alpha: Double = 1.0
  private var lamShrnk: Double = 0.001
  private var numIterations: Int = 100

  /**
   * Set the alpha. Default 1.0.
   */
  def setAlpha(step: Double): this.type = {
    this.alpha = step
    this
  }

  /**
   * Set the lambda shrinkage parameter. Default 0.001.
   */
  def setLamShrnk(regParam: Double): this.type = {
    this.lamShrnk = regParam
    this
  }

  /**
   * Set the number of iterations for CD. Default 100.
   */
  def setNumIterations(iters: Int): this.type = {
    this.numIterations = iters
    this
  }

  def optimize(data: RDD[(Double, Vector)], initialWeights: Vector, xy: Array[Double], numFeatures: Int, numRows: Long): List[(Double, Vector)] = {
    CoordinateDescent2.runCD(
      data,
      initialWeights,
      xy,
      alpha,
      lamShrnk,
      numIterations,
      numFeatures, numRows)
  }

  def optimize(data: RDD[(Double, Vector)], initialWeights: Vector, xy: Array[Double], lambdaIndex: Int, numFeatures: Int, numRows: Long): Vector = {
    CoordinateDescent2.runCD(
      data,
      initialWeights,
      xy,
      alpha,
      lamShrnk,
      numIterations,
      lambdaIndex,
      numFeatures, numRows)
  }

  //TODO - Temporary to allow testing multiple versions of CoordinateDescent with minimum code duplication - remove to Object method only later
  def computeXY(data: RDD[(Double, Vector)], numFeatures: Int, numRows: Long): Array[Double] = {
    CoordinateDescent2.computeXY(data, numFeatures, numRows)
  }
}

/**
 * :: DeveloperApi ::
 * Top-level method to run coordinate descent.
 */
@DeveloperApi
object CoordinateDescent2 extends Logging {

  def runCD(data: RDD[(Double, Vector)], initialWeights: Vector, xy: Array[Double], alpha: Double, lamShrnk: Double, numIterations: Int, numFeatures: Int, numRows: Long): List[(Double, Vector)] = {
    val lambdas = computeLambdas(xy, alpha, lamShrnk, numIterations, numIterations, numRows): Array[Double]
    optimize(convertToMatrix(data), initialWeights, xy, lambdas, alpha, lamShrnk, numIterations, numFeatures, numRows)
  }

  def runCD(data: RDD[(Double, Vector)], initialWeights: Vector, xy: Array[Double], alpha: Double, lamShrnk: Double, numIterations: Int, lambdaIndex: Int, numFeatures: Int, numRows: Long): Vector = {
    val lambdas = computeLambdas(xy, alpha, lamShrnk, numIterations, lambdaIndex + 1, numRows): Array[Double]
    optimize(convertToMatrix(data), initialWeights, xy, lambdas, alpha, lamShrnk, numIterations, numFeatures, numRows).last._2
  }

  //TODO - Persistence needs to done from the LR for optimum
  private def convertToMatrix(data: RDD[(Double, Vector)]): RDD[DenseMatrix] = {
    val matrixRDD = ToBeNamed.arrayToMatrix(data.map(row => row._2.toArray))
      .persist(StorageLevel.MEMORY_AND_DISK)
    logDebug(s"convertToMatrix() data: RDD[(Double, Vector)]: ${data.toDebugString}, matrixRDD: RDD[DenseMatrix]: ${matrixRDD.toDebugString}")
    matrixRDD
  }

  private def optimize(data: RDD[DenseMatrix], initialWeights: Vector, xy: Array[Double], lambdas: Array[Double], alpha: Double, lamShrnk: Double, numIterations: Int, numFeatures: Int, numRows: Long): List[(Double, Vector)] = {
    //data.persist(StorageLevel.MEMORY_AND_DISK)
    //logRDD("data before persist", data)
    var totalNumNewBeta = 0
    val results = new MutableList[(Double, Vector)]

    val indexStart = xy.zipWithIndex.filter(xyi => abs(xyi._1) > (lambdas(0) * alpha)).map(_._2)
    totalNumNewBeta += indexStart.length
    logNewBeta(indexStart.length, totalNumNewBeta)
    val xx = CDSparseMatrix2(numFeatures, indexStart)
    populateXXMatrix(data, indexStart, xx, numRows)

    loop(initialWeights, 0)

    /*loop to decrement lambda and perform iteration for betas*/
    @tailrec
    def loop(oldBeta: Vector, n: Int): Unit = {
      if (n < lambdas.length) {
        logDebug(s"Lambda number: ${n + 1}")
        val newLambda = lambdas(n)
        val (newBeta, numNewBeta) = cdIter(data, oldBeta, newLambda, alpha, xy, xx, numRows)
        totalNumNewBeta += numNewBeta
        logNewBeta(numNewBeta, totalNumNewBeta)
        results += Pair(newLambda, newBeta.copy)
        loop(newBeta, n + 1)
      }
    }
    //println(s"CD ET: ${sw.elapsedTime / 1000} seconds")
    data.unpersist()
    logDebug(s"totalNumNewBeta $totalNumNewBeta")
    results.toList
  }

  def logNewBeta(numNewBeta: Int, totalNumNewBeta: Int) = {
    if (numNewBeta > 0) {
      logDebug(s"numNewBeta: $numNewBeta,  totalNumNewBeta: $totalNumNewBeta")
    }
  }

  def computeXY(data: RDD[(Double, Vector)], numFeatures: Int, numRows: Long): Array[Double] = {
    val xy = data.treeAggregate(new InitLambda2(numFeatures))(
      (aggregate, row) => aggregate.compute(row),
      (aggregate1, aggregate2) => aggregate1.combine(aggregate2)).xy

    xy.map(_ / numRows)
  }

  def computeLambdas(xy: Array[Double], alpha: Double, lamShrnk: Double, lambdaRange: Int, numLambdas: Int, numRows: Long): Array[Double] = {
    logDebug(s"alpha: $alpha, lamShrnk: $lamShrnk, numIterations: $lambdaRange, numRows: $numRows")

    val maxXY = xy.map(abs).max(Ordering.Double)
    val lambdaInit = maxXY / alpha

    val lambdaMult = exp(scala.math.log(lamShrnk) / lambdaRange)

    val lambdas = new MutableList[Double]

    loop(lambdaInit, numLambdas)

    /*loop to decrement lambda and perform iteration for betas*/
    @tailrec
    def loop(oldLambda: Double, n: Int): Unit = {
      if (n > 0) {
        val newLambda = oldLambda * lambdaMult
        lambdas += newLambda
        loop(newLambda, n - 1)
      }
    }
    logDebug(s"lambdas: ${lambdas.mkString(",")}")
    lambdas.toArray
  }

  def populateXXMatrix(data: RDD[DenseMatrix], newIndexes: Array[Int], xx: CDSparseMatrix2, numRows: Long): Unit = {
    Timer("xCorrelation").start
    val correlatedX = xCorrelation(data, newIndexes, xx.numFeatures, numRows)
    Timer("xCorrelation").end
    Timer("xx.update").start
    xx.update(newIndexes, correlatedX)
    Timer("xx.update").end
  }

  def xCorrelation(data: RDD[DenseMatrix], newColIndexes: Array[Int], numFeatures: Int, numRows: Long): DenseMatrix = {
    val numNewBeta = newColIndexes.size

    val xx = data.treeAggregate(new XCorrelation2(newColIndexes, numFeatures))(
      (aggregate, row) => aggregate.compute(row),
      (aggregate1, aggregate2) => aggregate1.combine(aggregate2)).xx

    //xx.map { _.map(_ / numRows) }
    fromBreeze(toBreeze(xx) :/= (numRows.toDouble))
  }

  def S(z: Double, gamma: Double): Double = if (gamma >= abs(z)) 0.0 else (z / abs(z)) * (abs(z) - gamma)

  def cdIter(data: RDD[DenseMatrix], oldBeta: Vector, newLambda: Double, alpha: Double, xy: Array[Double], xx: CDSparseMatrix2, numRows: Long): (Vector, Int) = {
    var numNewBeta = 0
    //val eps = 0.01
    val eps = 0.001
    val numCDIter = 100
    val ridgePenaltyShrinkage = 1 + newLambda * (1 - alpha)
    val gamma = newLambda * alpha

    @tailrec
    def loop(beta: Vector, deltaBeta: Double, firstPass: Boolean, n: Int): Vector = {
      if (deltaBeta <= eps || n == 0) {
        beta
      } else {
        val betaStart = beta.copy
        Timer("coordinateWiseUpdate").start
        coordinateWiseUpdate(beta.toBreeze)
        Timer("coordinateWiseUpdate").end

        if (firstPass) {
          val newIndexes = xx.newIndices(beta.toBreeze)
          if (!newIndexes.isEmpty) {
            numNewBeta += newIndexes.size
            populateXXMatrix(data, newIndexes.toArray, xx, numRows)
          }
        }
        val sumDiff = (beta.toArray zip betaStart.toArray) map (b => abs(b._1 - b._2)) sum
        val sumBeta = beta.toArray.map(abs).sum
        val deltaBeta = sumDiff / sumBeta
        loop(beta, deltaBeta, false, n - 1)
      }
    }

    def coordinateWiseUpdate(beta: BV[Double]) = {
      for (j <- 0 until xx.numFeatures) {
        val xyj = xy(j) - xx.dot(j, beta)
        val uncBeta = xyj + beta(j)
        beta(j) = S(uncBeta, gamma) / ridgePenaltyShrinkage
      }
    }

    (loop(oldBeta, 100.0, true, numCDIter), numNewBeta)
  }
}

private class InitLambda2(numFeatures: Int) extends Serializable {

  lazy val xy: Array[Double] = Array.ofDim[Double](numFeatures)

  def compute(row: (Double, Vector)): this.type = {
    val loadedXY = xy
    val y = row._1
    val x = row._2.toArray
    var j = 0
    while (j < numFeatures) {
      loadedXY(j) += x(j) * y
      j += 1
    }
    this
  }

  def combine(other: InitLambda2): this.type = {
    val thisXX = xy
    val otherXX = other.xy
    var j = 0
    while (j < numFeatures) {
      thisXX(j) += otherXX(j)
      j += 1
    }
    this
  }
}

private[optimization] object BreezeUtil {
  def toBreeze(dm: DenseMatrix): BDM[Double] = {
    if (!dm.isTransposed) {
      new BDM[Double](dm.numRows, dm.numCols, dm.values)
    } else {
      val breezeMatrix = new BDM[Double](dm.numCols, dm.numRows, dm.values)
      breezeMatrix.t
    }
  }

  def fromBreeze(dm: BDM[Double]): DenseMatrix = {
    new DenseMatrix(dm.rows, dm.cols, dm.data, dm.isTranspose)
  }
}

private class XCorrelation2(newColIndexes: Array[Int], numFeatures: Int) extends Serializable {

  //var xx: Option[DenseMatrix] = None
  var xx: DenseMatrix = _

  def compute(row: DenseMatrix): this.type = {
    //assert(xx == None, "More than one matrix per partition")
    assert(xx == null, "More than one matrix per partition")
    xx = computeJxK(row, newColIndexes)
    this
  }

  def combine(other: XCorrelation2): this.type = {
    assert(xx != null, "Partition does not contain a matrix")
    assert(other.xx != null, "Other Partition does not contain a matrix")
    val dm = xx.toBreeze :+= other.xx.toBreeze 
    xx = fromBreeze(toBreeze(xx) :+= toBreeze(other.xx))
    //xx :+= other.xx
    this
  }

  //TODO - Test which way is the fastest in the entire CD process (computeJxK or computeKxJ)
  private def computeJxK(m: DenseMatrix, newColIndexes: Array[Int]): DenseMatrix = {
    val xk = sliceMatrixByColumns(m, newColIndexes)
    gemm(m.transpose, xk)
  }

  private def computeKxJ(m: DenseMatrix, newColIndexes: Array[Int]): DenseMatrix = {
    val xk = sliceMatrixByColumns(m, newColIndexes)
    gemm(xk.transpose, m)
  }

  //TODO - does breeze have this functionality?
  private def sliceMatrixByColumns(m: DenseMatrix, sliceIndices: Array[Int]): DenseMatrix = {
    val startTime = System.currentTimeMillis()
    val nIndices = sliceIndices.length
    val nRows = m.numRows
    val slice = Array.ofDim[Double](nRows * nIndices)
    var i = 0
    while (i < nIndices) {
      Array.copy(m.values, sliceIndices(i) * nRows, slice, i * nRows, nRows)
      i += 1
    }
    val sm = new DenseMatrix(nRows, sliceIndices.length, slice)
    println(s"sliceMatrixByColumns time: ${(System.currentTimeMillis() - startTime) / 1000} seconds")
    sm
  }

  private def gemm(
    A: DenseMatrix,
    B: DenseMatrix): DenseMatrix = {
    val C = DenseMatrix.zeros(A.numRows, B.numCols)
    BLAS.gemm(1.0, A, B, 1.0, C)
    C
  }
}