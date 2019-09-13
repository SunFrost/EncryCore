package benches

import java.security.SecureRandom
import java.util.concurrent.TimeUnit

import BlockProto.BlockProtoMessage
import HeaderProto.HeaderProtoMessage
import PayloadProto.PayloadProtoMessage
import TransactionProto.TransactionProtoMessage
import benches.SerializedBlockBenchmark.SerializedBlockBenchState
import benches.Utils._
import encryBenchmark.Settings
import org.encryfoundation.common.crypto.equihash.EquihashSolution
import org.encryfoundation.common.modifiers.history._
import org.encryfoundation.common.modifiers.mempool.transaction.TransactionProtoSerializer
import org.encryfoundation.common.utils.TaggedTypes.ModifierId
import org.encryfoundation.common.utils.constants.TestNetConstants
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.profile.GCProfiler
import org.openjdk.jmh.runner.options.{OptionsBuilder, TimeValue, VerboseMode}
import org.openjdk.jmh.runner.{Runner, RunnerException}
import scorex.crypto.hash.Digest32
import scorex.utils.Random
import supertagged.TaggedType

class SerializedBlockBenchmark {

//  @Benchmark
//  def serializeProtoBlockBench(stateBench: SerializedBlockBenchState, bh: Blackhole): Unit =
//    bh.consume {
//      BlockProtoSerializer.toProto(stateBench.block).toByteArray
//    }

//  @Benchmark
//  def deserializeHeaderBench(stateBench: SerializedBlockBenchState, bh: Blackhole): Unit =
//    bh.consume(
//      HeaderProtoSerializer.fromProto(HeaderProtoMessage.parseFrom(stateBench.headerBytes))
//    )

  @Benchmark
  def deserializePayloadBench(stateBench: SerializedBlockBenchState, bh: Blackhole): Unit = {
    bh.consume(
      PayloadProtoSerializer.fromProto(PayloadProtoMessage.parseFrom(stateBench.payloadBytes))
    )
  }

  @Benchmark
  def deserializeTransactionBench(stateBench: SerializedBlockBenchState, bh: Blackhole): Unit = {
    bh.consume(
      TransactionProtoSerializer.fromProto(TransactionProtoMessage.parseFrom(stateBench.transBytes))
    )
  }
}

object SerializedBlockBenchmark {

  val benchSettings: Settings = Settings.read

  @throws[RunnerException]
  def main(args: Array[String]): Unit = {
    val opt = new OptionsBuilder()
      .include(".*" + classOf[SerializedBlockBenchmark].getSimpleName + ".*")
      .forks(1)
      .threads(1)
      .warmupIterations(3)
      .measurementIterations(3)
      .mode(Mode.AverageTime)
      .timeUnit(TimeUnit.MILLISECONDS)
      .verbosity(VerboseMode.NORMAL)
      .addProfiler(classOf[GCProfiler])
      .warmupTime(TimeValue.milliseconds(500))
      .measurementTime(TimeValue.milliseconds(500))
      .build
    new Runner(opt).run
    //org.encryfoundation.common.utils.PerfomanceUtils.printTimes()
  }

  def genBlock(txCount: Int): Block = {
    val blockHeader = Header(99: Byte, ModifierId @@ Random.randomBytes(), Digest32 @@ Random.randomBytes(),99999L,
      199, 999L, TestNetConstants.InitialDifficulty, EquihashSolution(Seq(1, 2, 3)))

    val initialBoxes = generateInitialBoxes(txCount)
    val transactions = generatePaymentTransactions(initialBoxes, 1, 1)

    val blockPayload = Payload(ModifierId @@ Array.fill(32)(19: Byte), transactions)
    Block(blockHeader, blockPayload)
  }

  @State(Scope.Benchmark)
  class SerializedBlockBenchState {

    var block: Block = _
    var headerBytes, payloadBytes, transBytes: Array[Byte] = _

    @Setup
    def createStateForBenchmark(): Unit = {
      block = genBlock(3000)
      //headerBytes = HeaderProtoSerializer.toProto(block.header).toByteArray
      payloadBytes = PayloadProtoSerializer.toProto(block.payload).toByteArray
      transBytes = TransactionProtoSerializer.toProto(block.payload.txs.head).toByteArray
    }

    @TearDown
    def afterAll(): Unit = {
      org.encryfoundation.common.utils.PerfomanceUtils.printTimes()
    }
  }

  /*
    BlockSerializer
    txs         ser            deser
    ———————————-
    30tx ->     0.022ms 9.4ms
    300tx ->    1.7ms   86ms
    3000tx ->   169ms   761ms
    10000tx ->  2035ms  2578ms

    BlockProtoSerializer
    txs         ser            deser
    ———————————-
    30tx ->     0.039ms 9.5ms
    300tx ->    0.43ms  86ms
    3000tx ->   6ms     759ms->641ms->86ms
    10000tx ->  23ms    2520ms
   */

}