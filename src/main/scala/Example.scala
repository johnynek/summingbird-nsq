import com.twitter.algebird.Semigroup
import com.twitter.storehaus.ReadableStore
import com.twitter.storehaus.algebra.Mergeable
import com.twitter.summingbird.Source
import com.twitter.util.Future

import scala.util.Try

import scala.language.existentials

object Example {

  private class FakeReadableStore extends ReadableStore[String, Int] with Mergeable[String, Int]{

    private val intMap = new scala.collection.mutable.HashMap[String, Int]

    override def get(k: String): Future[Option[Int]] = Future.value(intMap.get(k))

    override def semigroup: Semigroup[Int] = Semigroup.intSemigroup

    override def merge(kv: (String, Int)): Future[Option[Int]] = {
      val (k,v) = kv
      val prevV = intMap.get(k)
      val newV = semigroup.sumOption(Seq(Some(v), prevV).flatten).get
      intMap.put(k, newV)
      Future.value(prevV)
    }
  }

  def main (args: Array[String]) {
    val clientConfig = NSQClientConfig("test", "summingbird-nsq", Seq(("localhost", 4161)))

    val source = new NSQSource[String](clientConfig, bytes => Try(new String(bytes, "UTF-8")).toOption)
    val sbSource = Source[NSQ, String](source)
    val store = new FakeReadableStore
    val mapped = sbSource.map{ s => (s, 1)}
    val summed = mapped.sumByKey(store)

    val stream = (new NSQ).plan(summed)
    // throw away result cause I was having trouble getting types to match.
    // should still preserve success / failure
    println("OK")
    val nulledStream = stream.map(_.map(_ => Unit))

    println("Ready to start up the consumer")
    source.open()
    println("Started the consumer, reading from stream")

    receiveOutputStream(nulledStream)
  }

  def receiveOutputStream(out: NSQ#Plan[Unit.type]) = {
    out.foreach{
        case NSQWrappedValue(msg, result) =>
          result
            .onSuccess(_ => msg.finished())
            .onFailure(_ => msg.requeue())
    }
  }

}
