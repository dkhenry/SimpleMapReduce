# SimpleMapReduce

A simple Map-Reduce Example in 100 lines of scala. 
This was done as a proof of concept to see what could easly be done with Akka. 


## Requirements

All You Should need ot run this example is SBT 11.0+ 

## Explanation

Lets walk through the code. The first thing we need to do is establish the signaling beetween Actors. For this specific example I am going to use line oriented data [String] and the results will be in the form of Map[String,Int]. While I could have added the data set to the Request signal, I wanted to model something with an unknown distributed data set 

```
case class Data(s: String)
case class Request(m: String => Map[String,Int],r: List[Map[String,Int]] => Map[String,Int])
case class Job(router: ActorRef, workers: Int, request:Request)
case class Done()
```

Next we need a host process to serve as a data sink and to run our map jobs. The data will be stored as a simple ArrayBuffer. This Actor is only a shell for storing data and running passed in funcitons so his code is short.

```
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
```

Now we need to start these guys up and figure out a way to send them data in a balanced fashion. For this I am going to leverage Akka's built in routers. This will set up to (workers) actors and set them in a round robin router. Now every message I send to dataRouter will be forwarded to one of my worker actors in a round robin sequence. 

```
val routees = for( x <- (1 to workers) ) yield system.actorOf(Props[Worker]) 
val dataRouter = system.actorOf(Props[Worker].withRouter(RoundRobinRouter(routees = routees)))
```

Great I can send Lines of Data to a pool of actors and they will store it. If they ever get a Request() they will process each entry in their data set though it and send the results back to whoever made the request. Now I just need someone to make requests. 

For this I am also going to leverage Akka and its routers, but I am going to do something that might not be supported, but does work. I am going to reuse my actors and just assign them to a second router 

```
    val requestRouter = system.actorOf(Props[Worker].withRouter(BroadcastRouter(routees = routees)))
```  

now message send to requestRouter will get forwarded to _all_ the worker actors. We can encapsiluate the logic of running a Job into its own actor which will make it easy to retrieve the return messages. This is one of the parts that get ugly. I tried to figure out a clean way of doing this, but I had to settle on using a counter to count the replies coming back from the workers. Its ugly, I know, but it gets the job done. We are going to wait for a Job() to come in and start running it. To pretend we are a little robust we are going to store additional jobs as ArrayBuffer and deal with them after we finish the current job. Akka has it built in that if I use the ask (?) pattern the returned Future will block until we send that Actor back a message. So in this case we are going to archive the ActorRef of the sender and wait to send him back a responce until we get all the data from the workers. 

```
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
```

Now we create our Job Runner 
```
val runner = system.actorOf(Props[Runner],"reducer")
```

Everything is set up now. We can seed our data set and run a sample job 
```
def countGets(s: String) :Map[String,Int] = { 
  val Nginx(ip,day,month,year,time,method,_) = s
	Thread.sleep(1000)
  Map(ip -> 1, day -> 1, month -> 1, year ->1,method->1)
}

def reduceGets(data: List[Map[String,Int]]): Map[String,Int] = { 
	 data.foldLeft(List[(String,Int)]())( (sum,next) => (sum ++ next.toList) ).groupBy(_._1).map { case (k,v) => k -> v.reduce{ (y,z) => (y._1,y._2+z._2)}._2 }
}

(1 to 30) foreach {
      i => dataRouter ! DATA
}
val result = runner ? Job(requestRouter,workers,Request(countGets,reduceGets))
println {"Results: "}
Await.result(result,timeout.duration).asInstanceOf[Map[String,Int]].foreach { x => println { x._1 + " : " + x._2 } }
```

## Next Steps 

This is the real power of Akka. Take the router lines and do this to them 
```
import akka.actor.{ Address, AddressFromURIString }
val addresses = Seq(
  Address("akka", "remotesys1", "otherhost1", 1234),
  Address("akka", "remotesys2", "otherhost2", 1234)
)
val routerRemote = system.actorOf(Props[ExampleActor1].withRouter(RemoteRouterConfig(RoundRobinRouter(5), addresses)))
```

And we have a distributed Map-Reduce. We will need to run a host process on the remote machiens that has the definitions of the two actor types, we will also have to configure them to listen remotly. Thats documented here http://doc.akka.io/docs/akka/2.0/scala/remoting.html

## Forthcomming 
 * Distributed Map-Reduce 
 * Split the Reduce over multiple threads ( Current bottleneck )
 * Support a unified data set ( DB )
 * Pass data in the request 
 * Make Distributed Map-Reduce elastic ( add / remove workers ) 

## Takeaway

Akka is great. Its well designed and easy to use, however its very static. Adding aditional workers after the router has been created isn't supported out of the box. I also don't know how well it would stand up during a failure situation. Akka has alot of support built in for monitored Actors, but I couldn't find a way to make an elastic system. Most changes needed to be done either in the config file or during the initial creation of the system. However Map-Reduce with arbitrary function in 100 lines isn't bad. 
