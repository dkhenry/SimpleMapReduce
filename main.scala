/** SimpleMapReduce
 *
 *  @author: dkhenry
 *  @date: 30 July 2012 
 *  @copyright: GPLv3
 */ 
import akka.actor._
import akka.routing._
import scala.collection.mutable.ArrayBuffer
import akka.dispatch.Await
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout

case class Data(s: String)
case class Request(m: String => Map[String,Int],r: List[Map[String,Int]] => Map[String,Int])
case class Job(router: ActorRef, workers: Int, request:Request)
case class Done()

class Worker extends Actor {
  val dataset : ArrayBuffer[String] = ArrayBuffer[String]()
  def receive = { 
    case d: Data => dataset += d.s
    case r: Request =>  {
      dataset foreach { x => sender ! r.m(x) }
	  sender ! Done()
    }
  }
}

class Runner() extends Actor { 
  var pending: ArrayBuffer[(Job,ActorRef)] = ArrayBuffer()
  var active: Option[(Job,ActorRef)] = None
  var results: ArrayBuffer[Map[String,Int]] = ArrayBuffer()
  var responded = 0 
  
  def runJob(j: Job,sender: ActorRef) = { 
    results = ArrayBuffer()
    j.router ! j.request
    active = Some((j,sender))
	responded = 0; 
  }

  def receive = { 
    case j: Job => active match { 
      case None => runJob(j,sender)
      case _ => {
        pending +=( (j,sender) )
      }
    }
    case m: Map[String,Int] => results += m
	case d: Done => responded match { 
		 	case x if x < active.get._1.workers - 1 => responded = responded +1 
			case _ => {
				 active.get._2 ! active.get._1.request.r(results.toList) 
				 pending = pending.toList match {
				 		 case Nil => ArrayBuffer()
						 case head :: tail => runJob(head._1,head._2) ; ArrayBuffer() ++ tail
				 }
			}
  	}
  }
}

object SimpleMapReduce { 
  val DATA=Data("95.108.150.235 - - [06/Jul/2012:20:03:20 -0400] \"GET / HTTP/1.1\" 401 195 \"-\" \"Mozilla/5.0 (compatible; YandexBot/3.0; +http://yandex.com/bots)\" \"-\"")
  val Nginx = """([\d\.]*) - - \[(\d\d)\/(\w*)\/(\d*):([\d: -]*)\] \"(\w*)(.*)""".r

  def countGets(s: String) :Map[String,Int] = { 
  	val Nginx(ip,day,month,year,time,method,_) = s
	Thread.sleep(1000)
    Map(ip -> 1, day -> 1, month -> 1, year ->1,method->1)
  }

  def reduceGets(data: List[Map[String,Int]]): Map[String,Int] = { 
	 data.foldLeft(List[(String,Int)]())( (sum,next) => (sum ++ next.toList) ).groupBy(_._1).map { case (k,v) => k -> v.reduce{ (y,z) => (y._1,y._2+z._2)}._2 }
  }

  def main(args: Array[String]) { 
	val workers = 4

	println {"Starting Simple Map Reduce System"}
    val system = ActorSystem("SimpleMapReduce")
	implicit val timeout = Timeout(300 seconds)

    // Create a simple pool of actors 
	val routees = for( x <- (1 to workers) ) yield system.actorOf(Props[Worker]) 

	// Create the routers 
    val dataRouter = system.actorOf(Props[Worker].withRouter(RoundRobinRouter(routees = routees)))
    val requestRouter = system.actorOf(Props[Worker].withRouter(BroadcastRouter(routees = routees)))
    val runner = system.actorOf(Props[Runner],"reducer")

	val start = System.nanoTime
    (1 to 30) foreach {
      i => dataRouter ! DATA
    }
    val result = runner ? Job(requestRouter,workers,Request(countGets,reduceGets))
	println {"Results: "}
    Await.result(result,timeout.duration).asInstanceOf[Map[String,Int]].foreach { x => println { x._1 + " : " + x._2 } }

	val delta = (System.nanoTime - start) / 1000 / 1000
    system.shutdown()
    println {"All Done. It Took "+ delta + "ms"}
  }
}
