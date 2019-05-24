package benches

import java.util.concurrent.TimeUnit
import benches.SerializedDataTxBenchmark.SerializedDataBenchState
import benches.Utils._
import encry.modifiers.mempool.{Transaction, TransactionSerializer}
import encry.modifiers.state.box.AssetBox
import encryBenchmark.Settings
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.profile.GCProfiler
import org.openjdk.jmh.runner.{Runner, RunnerException}
import org.openjdk.jmh.runner.options.{OptionsBuilder, TimeValue, VerboseMode}

class SerializedDataTxBenchmark {

  @Benchmark
  def deserializeDataTransactionsBench(stateBench: SerializedDataBenchState, bh: Blackhole): Unit =
    bh.consume(stateBench.serializedTransactions.map(b => TransactionSerializer.parseBytes(b)))

  @Benchmark
  def serializeDataTransactionsBench(stateBench: SerializedDataBenchState, bh: Blackhole): Unit =
    bh.consume(stateBench.initialTransactions.map(tx => tx.bytes))
}

object SerializedDataTxBenchmark {

  val benchSettings: Settings = Settings.read

  @throws[RunnerException]
  def main(args: Array[String]): Unit = {
    val opt = new OptionsBuilder()
      .include(".*" + classOf[SerializedDataTxBenchmark].getSimpleName + ".*")
      .forks(1)
      .threads(1)
      .warmupIterations(benchSettings.benchesSettings.warmUpIterations)
      .measurementIterations(benchSettings.benchesSettings.measurementIterations)
      .mode(Mode.AverageTime)
      .timeUnit(TimeUnit.SECONDS)
      .verbosity(VerboseMode.EXTRA)
      .addProfiler(classOf[GCProfiler])
      .warmupTime(TimeValue.milliseconds(benchSettings.benchesSettings.warmUpTime))
      .measurementTime(TimeValue.milliseconds(benchSettings.benchesSettings.measurementTime))
      .build
    new Runner(opt).run
  }

  @State(Scope.Benchmark)
  class SerializedDataBenchState {

    var initialBoxes: IndexedSeq[AssetBox] = IndexedSeq.empty[AssetBox]
    var initialTransactions: IndexedSeq[Transaction] = IndexedSeq.empty[Transaction]
    var serializedTransactions: IndexedSeq[Array[Byte]] = IndexedSeq.empty[Array[Byte]]

    @Setup
    def createStateForBenchmark(): Unit = {
      initialBoxes = generateInitialBoxes(benchSettings.serializedDataBenchSettings.totalBoxesNumber)
      initialTransactions = generateDataTransactions(
        initialBoxes,
        benchSettings.serializedDataBenchSettings.numberOfInputs,
        benchSettings.serializedDataBenchSettings.numberOfOutputs,
        benchSettings.serializedDataBenchSettings.bytesQty
      )
      serializedTransactions = initialTransactions.map(tx => tx.bytes)
    }
  }
}