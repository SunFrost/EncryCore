package encry.utils

object PerfomanceUtils {

  private val sb = new StringBuffer()
  private var timeMap: Map[String, Long] = Map()

  def time[T](label: String, body: => T): T = {
    val start = System.currentTimeMillis()
    val result = body
    sb.append(s"Time $label: ${System.currentTimeMillis() - start}ms\n")
    result
  }

  def start(label: String) {
    timeMap += label -> System.currentTimeMillis()
  }

  def finish(label: String) {
    timeMap.get(label).foreach { start =>
      sb.append(s"Time $label: ${System.currentTimeMillis() - start}ms\n")
      timeMap -= label
    }
  }

  def printTimes() {
    println(sb.toString)
  }

}
