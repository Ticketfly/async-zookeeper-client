
package com.github.bigtoast.zookeeper

import org.slf4j.LoggerFactory
import org.apache.zookeeper._
import java.util.concurrent.{TimeUnit, CountDownLatch}
import org.apache.zookeeper.Watcher.Event.{EventType, KeeperState}
import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.ZooDefs.Ids
import scala.collection.JavaConversions._
import org.apache.zookeeper.AsyncCallback._
import scala.concurrent.{Await, Future, ExecutionContext, Promise}
import org.apache.zookeeper.KeeperException.Code
import java.util
import scala.concurrent.duration._
import scala.util.{Success, Failure}


sealed trait AsyncResponse {
  def ctx: Option[Any]

  def code: Code
}

object AsyncResponse {

  trait SuccessAsyncResponse extends AsyncResponse {
    val code = Code.OK
  }

  case class FailedAsyncResponse(exception: KeeperException, path: Option[String], stat: Option[Stat], ctx: Option[Any]) extends RuntimeException(exception) with AsyncResponse {
    val code = exception.code
  }

  case class ChildrenResponse(children: Seq[String], path: String, stat: Stat, ctx: Option[Any]) extends SuccessAsyncResponse

  case class StatResponse(path: String, stat: Stat, ctx: Option[Any]) extends SuccessAsyncResponse

  case class VoidResponse(path: String, ctx: Option[Any]) extends SuccessAsyncResponse

  case class DataResponse(data: Option[Array[Byte]], path: String, stat: Stat, ctx: Option[Any]) extends SuccessAsyncResponse

  case class DeleteResponse(children: Seq[String], path: String, stat: Stat, ctx: Option[Any]) extends SuccessAsyncResponse

  case class StringResponse(name: String, path: String, ctx: Option[Any]) extends SuccessAsyncResponse


}

/** This just provides some implicits to help working with byte arrays. They are totally optional */
object AsyncZooKeeperClient {
  implicit def bytesToSome(bytes: Array[Byte]): Option[Array[Byte]] = Some(bytes)

  implicit val stringDeser: Array[Byte] => String = bytes => bytes.view.map(_.toChar).mkString

  class Deserializer(bytes: Array[Byte]) {
    def deser[T](implicit d: Array[Byte] => T) = d(bytes)
  }

  implicit def toDeser(bytes: Array[Byte]): Deserializer = new Deserializer(bytes)

  def apply(
             servers:         String,
             sessionTimeout:  Int,
             connectTimeout:  Int,
             basePath:        String,
             watcher:         Option[AsyncZooKeeperClient => Unit]
             )(implicit ec: ExecutionContext): AsyncZooKeeperClient = {
    val cli = new AsyncZooKeeperClientImpl(servers, sessionTimeout, connectTimeout, basePath, watcher)
    cli.connect
    cli
  }
}

/**
 *
 * Scala wrapper around the async ZK api. This is based on the twitter scala wrapper now maintained by 4square.
 * https://github.com/foursquare/scala-zookeeper-client
 *
 * It uses Akka 2.0 Futures. Once our company gets on scala 2.10 I will refactor to use SIP 14 Futures.
 *
 * I didn't implement any ACL stuff because I never use that shiz.
 *
 * You can pass in a base path (defaults to "/") which will be prepended to any path that does not start with a "/".
 * This allows you to specify a context to all requests. Absolute paths, those starting with "/", will not have the
 * base path prepended.
 *
 * On connection the base path will be created if it does not already exist.
 *
 *
 */
trait AsyncZooKeeperClient {

  import AsyncResponse._


  /** get the underlying ZK connection */
  def underlying: Option[ZooKeeper]

  /**
   * Given a string representing a path, return each subpath
   * Ex. subPaths("/a/b/c", "/") == ["/a", "/a/b", "/a/b/c"]
   */
  def subPaths(path: String, sep: Char) :List[String]

  /**
   * helper method to convert a zk response in to a client reponse and handle the errors
   * */
  def handleResponse[T](rc: Int, path: String, p: Promise[T], stat: Stat, cxt: Option[Any])(f: => T): Future[T]

  /** Wrapper around the ZK exists method. Watch is hardcoded to false.
    *
    * @see <a target="_blank" href="http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#exists(java.lang.String, boolean, org.apache.zookeeper.AsyncCallback.StatCallback, java.lang.Object)">
    *      http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#exists(java.lang.String, boolean, org.apache.zookeeper.AsyncCallback.StatCallback, java.lang.Object)</a>
    */
  def exists(path: String, ctx: Option[Any] = None, watch: Option[Watcher] = None): Future[StatResponse]


  /** Wrapper around the ZK getChildren method. Watch is hardcoded to false.
    *
    * @see <a target="_blank" href="http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#getChildren(java.lang.String, boolean, org.apache.zookeeper.AsyncCallback.Children2Callback, java.lang.Object)">
    *      http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#getChildren(java.lang.String, boolean, org.apache.zookeeper.AsyncCallback.Children2Callback, java.lang.Object)</a>
    */
  def getChildren(path: String, ctx: Option[Any] = None, watch: Option[Watcher] = None): Future[ChildrenResponse]


  /** close the underlying zk connection */
  def close() :Unit

  /** Checks the connection by checking existence of "/" */
  def isAlive(implicit ec: ExecutionContext): Future[Boolean]

  /** Check the connection synchronously */
  def isAliveSync: Boolean


  /** Recursively create a path with persistent nodes and no watches set. */
  def createPath(path: String)(implicit ec: ExecutionContext): Future[VoidResponse]


  /** Create a path with OPEN_ACL_UNSAFE hardcoded
    * @see <a target="_blank" href="http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#create(java.lang.String, byte[], java.util.List, org.apache.zookeeper.CreateMode, org.apache.zookeeper.AsyncCallback.StringCallback, java.lang.Object)">
    *      http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#create(java.lang.String, byte[], java.util.List, org.apache.zookeeper.CreateMode, org.apache.zookeeper.AsyncCallback.StringCallback, java.lang.Object)
    *      </a>
    */
  def create(path: String, data: Option[Array[Byte]], createMode: CreateMode, ctx: Option[Any] = None)(implicit ec: ExecutionContext): Future[StringResponse]


  /** Create a node and then return it. Under the hood this is a create followed by a get. If the stat or data is not
    * needed use a plain create which is cheaper.
    */
  def createAndGet(path: String, data: Option[Array[Byte]], createMode: CreateMode, ctx: Option[Any] = None, watch: Option[Watcher] = None)(implicit ec: ExecutionContext): Future[DataResponse]


  /** Return the node if it exists, otherwise create a new node with the data passed in. If the node is created a get will
    * be called and the value returned. In case of a race condition where a two or more requests are executed at the same
    * time and one of the creates will fail with a NodeExistsException, it will be handled and a get will be called.
    */
  def getOrCreate(path: String, data: Option[Array[Byte]], createMode: CreateMode, ctx: Option[Any] = None)(implicit ec: ExecutionContext): Future[DataResponse]


  /** Wrapper around zk getData method. Watch is hardcoded to false.
    * @see <a target="_blank" href="http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#getData(java.lang.String, boolean, org.apache.zookeeper.AsyncCallback.DataCallback, java.lang.Object)">
    *      http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#getData(java.lang.String, boolean, org.apache.zookeeper.AsyncCallback.DataCallback, java.lang.Object)
    *      </a>
    */
  def get(path: String, ctx: Option[Any] = None, watch: Option[Watcher] = None): Future[DataResponse]


  /** Wrapper around the zk setData method.
    * @see <a target="_blank" href="http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#setData(java.lang.String, byte[], int, org.apache.zookeeper.AsyncCallback.StatCallback, java.lang.Object)">
    *      http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#setData(java.lang.String, byte[], int, org.apache.zookeeper.AsyncCallback.StatCallback, java.lang.Object)
    *      </a>
    */
  def set(path: String, data: Option[Array[Byte]], version: Int = -1, ctx: Option[Any] = None): Future[StatResponse]


  /** Wrapper around zk delete method.
    * @see <a target="_blank" href="http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#delete(java.lang.String, int, org.apache.zookeeper.AsyncCallback.VoidCallback, java.lang.Object)">
    *      http://zookeeper.apache.org/doc/r3.4.1/api/org/apache/zookeeper/ZooKeeper.html#delete(java.lang.String, int, org.apache.zookeeper.AsyncCallback.VoidCallback, java.lang.Object)
    *      </a>
    *
    *      The version only applies to the node indicated by the path. If force is true all children will be deleted even if the
    *      version doesn't match.
    *
    * @param force Delete all children of this node
    */
  def delete(path: String, version: Int = -1, ctx: Option[Any] = None, force: Boolean = false)(implicit ec: ExecutionContext): Future[VoidResponse]


  /** Delete all the children of a node but not the node. */
  def deleteChildren(path: String, ctx: Option[Any] = None)(implicit ec: ExecutionContext): Future[VoidResponse]


  /** Set a persistent watch on data.
    * @see watchData
    */
  def watchData(path: String)(onData: (String, Option[DataResponse]) => Unit)(implicit ec: ExecutionContext): Future[DataResponse]


  /** set a persistent watch on a node listening for data changes. If a NodeDataChanged event is received or a
    * NodeCreated event is received the DataResponse will be returned with the new data and the watch will be
    * reset. If a None or NodeChildrenChanged the watch will be reset returning nothing. If the node is deleted
    * receiving a NodeDeleted event None will be returned.
    *
    * In the event of an error nothing will be returned but errors will be logged.
    *
    * @param path       relative or absolute
    * @param persistent if this is false the watch will not be reset after receiving an event. ( normal zk behavior )
    * @param onData     callback executed on data events. None on node deleted event. path will always be absolute regardless
    *                   of what is passed into the function
    * @return initial data. If this returns successfully the watch was set otherwise it wasn't
    */
  def watchData(path: String, persistent: => Boolean)(onData: (String, Option[DataResponse]) => Unit)(implicit ec: ExecutionContext): Future[DataResponse]


  /** Sets a persistent child watch.
    * @see watchChildren
    */
  def watchChildren(path: String)(onKids: ChildrenResponse => Unit)(implicit ec: ExecutionContext): Future[ChildrenResponse]


  /** set a persistent watch on if the children of this node change. If they do the updated ChildResponse will be returned.
    *
    * @param path Path
    * @param persistent If true, then persistt
    * @param onKids On Kids
    * @return
    */
  def watchChildren(path: String, persistent: => Boolean)(onKids: ChildrenResponse => Unit)(implicit ec: ExecutionContext): Future[ChildrenResponse]



  /**
   * sets the client watcher. reconnecting on session expired is handled by the client and doesn't need to be handled
   * here.
   */
  def watchConnection(onState: KeeperState => Unit) :Unit
}

private[zookeeper] class AsyncZooKeeperClientImpl(
          val servers: String,
          val sessionTimeout: Int,
          val connectTimeout: Int,
          val basePath: String,
          watcher: Option[AsyncZooKeeperClient => Unit]
) extends AsyncZooKeeperClient {

  import AsyncResponse._

  private val log = LoggerFactory.getLogger(this.getClass)

  @volatile private var clientWatcher: Option[Watcher] = None

  @volatile private var zk: ZooKeeper = null

  override def underlying: Option[ZooKeeper] = Option(zk)

  /**
   * connect() attaches to the remote zookeeper and sets an instance variable.
   */
  private[zookeeper] def connect(implicit ec: ExecutionContext): Unit = {
    import KeeperState._
    val connectionLatch = new CountDownLatch(1)
    val assignLatch = new CountDownLatch(1)

    if (zk != null) {
      zk.close()
    }

    zk = new ZooKeeper(servers, sessionTimeout, new Watcher {
      def process(event: WatchedEvent) = {
        assignLatch.await()
        event.getState match {
          case SyncConnected => connectionLatch.countDown()
          case Expired => connect
          case _ =>
        }
        clientWatcher.foreach {
          _.process(event)
        }
      }
    })
    assignLatch.countDown()
    log.info("Attempting to connect to zookeeper servers {}", servers)
    connectionLatch.await(sessionTimeout.toLong, TimeUnit.MILLISECONDS)
    try {
      isAliveSync
      Await.result(createPath(""), 10 seconds)
      ()
    } catch {
      case e: Throwable =>
        val errorMsg = s"Could not connect to zookeeper ensemble: $servers. Connection timed out after $connectTimeout milliseconds!"
        log.error(errorMsg, e)
        throw new RuntimeException(errorMsg, e)
    }
  }


  private[zookeeper] def handleNull(op: Option[Array[Byte]]): Array[Byte] = if (op == null) null else op.orNull


  override def subPaths(path: String, sep: Char) =
    path.split(sep).toList match {
      case Nil => Nil
      case l :: tail =>
        tail.foldLeft[List[String]](Nil) {
          (xs, x) => (xs.headOption.getOrElse("") + sep.toString + x) :: xs
        }.reverse
    }


  /** Create a zk path from a relative path. If an absolute path is passed in the base path will not be prepended.
    * Trailing '/'s will be stripped except for the path '/' and all '//' will be translated to '/'.
    *
    * @param path relative or absolute path
    * @return absolute zk path
    */
  protected[zookeeper] def mkPath(path: String) = (if (path startsWith "/") {
    path
  } else {
    s"$basePath/$path"
  }).replaceAll("//", "/") match {
    case str if str.length > 1 => str.stripSuffix("/")
    case str => str
  }


  override def handleResponse[T](rc: Int, path: String, p: Promise[T], stat: Stat, cxt: Option[Any])(f: => T): Future[T] = {
    Code.get(rc) match {
      case Code.OK => p.success(f).future
      case error if path == null => p.failure(FailedAsyncResponse(KeeperException.create(error), Option(path), Option(stat), cxt)).future
      case error => p.failure(FailedAsyncResponse(KeeperException.create(error, path), Option(path), Option(stat), cxt)).future
    }
  }


  override def exists(path: String, ctx: Option[Any] = None, watch: Option[Watcher] = None): Future[StatResponse] = {
    val p = Promise[StatResponse]()
    zk.exists(mkPath(path), watch.orNull, new StatCallback {
      def processResult(rc: Int, path: String, ignore: Any, stat: Stat) = {
        handleResponse(rc, path, p, stat, ctx)(StatResponse(path, stat, ctx))
        ()
      }
    }, ctx)
    p.future
  }


  override def getChildren(path: String, ctx: Option[Any] = None, watch: Option[Watcher] = None): Future[ChildrenResponse] = {
    val p = Promise[ChildrenResponse]()
    zk.getChildren(mkPath(path), watch.orNull, new Children2Callback {
      def processResult(rc: Int, path: String, ignore: Any, children: util.List[String], stat: Stat) = {
        handleResponse(rc, path, p, stat, ctx)(ChildrenResponse(children.toSeq, path, stat, ctx))
        ()
      }
    }, ctx)
    p.future
  }


  override def close() = zk.close()


  override def isAlive(implicit ec: ExecutionContext): Future[Boolean] = exists("/") map {
    _.stat.getVersion >= 0
  }


  override def isAliveSync: Boolean = try {
    zk.exists("/", false)
    true
  } catch {
    case e: Throwable =>
      log.warn("ZK not connected in isAliveSync", e)
      false
  }


  override def createPath(path: String)(implicit ec: ExecutionContext): Future[VoidResponse] = {
    Future.sequence {
      for {
        subPath <- subPaths(mkPath(path), '/')
      } yield {
        create(subPath, null, CreateMode.PERSISTENT).recover {
          case e: FailedAsyncResponse if e.code == Code.NODEEXISTS => VoidResponse(subPath, None)
        }
      }
    } map {
      _ =>
        VoidResponse(path, None)
    }
  }


  override def create(path: String, data: Option[Array[Byte]], createMode: CreateMode, ctx: Option[Any] = None)(implicit ec: ExecutionContext): Future[StringResponse] = {
    val p = Promise[StringResponse]()
    zk.create(mkPath(path), handleNull(data), Ids.OPEN_ACL_UNSAFE, createMode, new StringCallback {
      def processResult(rc: Int, path: String, ignore: Any, name: String) = {
        handleResponse(rc, path, p, null, ctx)(StringResponse(name, path, ctx))
        ()
      }
    }, ctx)
    p.future
  }


  override def createAndGet(path: String, data: Option[Array[Byte]], createMode: CreateMode, ctx: Option[Any] = None, watch: Option[Watcher] = None)(implicit ec: ExecutionContext): Future[DataResponse] = {
    create(path, data, createMode, ctx) flatMap { _ => get(path, ctx, watch = watch)}
  }


  override def getOrCreate(path: String, data: Option[Array[Byte]], createMode: CreateMode, ctx: Option[Any] = None)(implicit ec: ExecutionContext): Future[DataResponse] = {
    get(path) recoverWith {
      case FailedAsyncResponse(e: KeeperException.NoNodeException, _, _, _) =>
        create(path, data, createMode, ctx) flatMap {
          _ => get(path)
        } recoverWith {
          case FailedAsyncResponse(e: KeeperException.NodeExistsException, _, _, _) =>
            get(path)
        }
    }
  }


  override def get(path: String, ctx: Option[Any] = None, watch: Option[Watcher] = None): Future[DataResponse] = {
    val p = Promise[DataResponse]()
    zk.getData(mkPath(path), watch.orNull, new DataCallback {
      def processResult(rc: Int, path: String, ignore: Any, data: Array[Byte], stat: Stat) = {
        handleResponse(rc, path, p, stat, ctx)(DataResponse(Option(data), path, stat, ctx))
        ()
      }
    }, ctx)
    p.future
  }


  override def set(path: String, data: Option[Array[Byte]], version: Int = -1, ctx: Option[Any] = None): Future[StatResponse] = {
    val p = Promise[StatResponse]()
    zk.setData(mkPath(path), handleNull(data), version, new StatCallback {
      def processResult(rc: Int, path: String, ignore: Any, stat: Stat) = {
        handleResponse(rc, path, p, stat, ctx)(StatResponse(path, stat, ctx))
        ()
      }
    }, ctx)
    p.future
  }


  override def delete(path: String, version: Int = -1, ctx: Option[Any] = None, force: Boolean = false)(implicit ec: ExecutionContext): Future[VoidResponse] = {
    if (force) {
      deleteChildren(path, ctx) flatMap {
        _ => delete(path, version, ctx, force = false)
      }
    } else {
      val p = Promise[VoidResponse]()
      zk.delete(mkPath(path), version, new VoidCallback {
        def processResult(rc: Int, path: String, ignore: Any) = {
          handleResponse(rc, path, p, null, ctx)(VoidResponse(path, ctx))
          ()
        }
      }, ctx)
      p.future
    }
  }


  override def deleteChildren(path: String, ctx: Option[Any] = None)(implicit ec: ExecutionContext): Future[VoidResponse] = {

    def recurse(p: String): Future[VoidResponse] = for {
      response    <- getChildren(p)
      seq         <- Future.sequence(response.children.map(child => recurse(s"${mkPath(p)}/$child")))
      delete      <- if (p == path) Future.successful(VoidResponse(path, ctx)) else delete(p, -1, ctx)
    } yield delete

    recurse(path)
  }


  override def watchData(path: String)(onData: (String, Option[DataResponse]) => Unit)(implicit ec: ExecutionContext): Future[DataResponse] = watchData(path, persistent = true)(onData)


  override def watchData(path: String, persistent: => Boolean)(onData: (String, Option[DataResponse]) => Unit)(implicit ec: ExecutionContext): Future[DataResponse] = {
    val w = new Watcher {

      def ifPersist: Option[Watcher] = {
        if (persistent) Some(this) else None
      }

      def process(event: WatchedEvent) = event.getType match {
        case e if e == EventType.None || e == EventType.NodeChildrenChanged =>
          exists(path, watch = ifPersist)
          ()

        case e if e == EventType.NodeCreated || e == EventType.NodeDataChanged =>
          get(path, watch = ifPersist).onComplete {
            case Failure(error) =>
              log.error(s"Error on NodeCreated callback for path ${mkPath(path)}", error)
            case Success(data) =>
              onData(mkPath(path), Some(data))
          }

        case EventType.NodeDeleted =>
          get(path, watch = ifPersist) onComplete {
            case Failure(error: FailedAsyncResponse) if error.code == Code.NONODE =>
              onData(mkPath(path), None)
            case Success(data) =>
              onData(mkPath(path), Some(data))
            case Failure(error) =>
              log.error(s"Error on NodeCreated callback for path ${mkPath(path)}", error)
          }
      }
    }

    get(path, watch = Some(w))
  }


  override def watchChildren(path: String)(onKids: ChildrenResponse => Unit)(implicit ec: ExecutionContext): Future[ChildrenResponse] = watchChildren(path, persistent = true)(onKids)


  override def watchChildren(path: String, persistent: => Boolean)(onKids: ChildrenResponse => Unit)(implicit ec: ExecutionContext): Future[ChildrenResponse] = {
    val p = mkPath(path)
    val w = new Watcher {
      def ifPersist: Option[Watcher] = {
        if (persistent) Some(this) else None
      }

      def process(event: WatchedEvent) = event.getType match {
        case EventType.NodeChildrenChanged =>
          getChildren(p, watch = ifPersist) onComplete {
            case Failure(error) =>
              log.error(s"Error on NodeChildrenChanged callback for path $p", error)
            case Success(kids) =>
              onKids(kids)
          }
          exists(p, watch = ifPersist)
          ()

        case e =>
          log.error(s"Not expecting to get event $e in a watchChildren")

      }
    }
    getChildren(p, watch = Some(w))
  }


  override def watchConnection(onState: KeeperState => Unit) = {
    val w = new Watcher {
      def process(event: WatchedEvent) = onState(event.getState)
    }
    clientWatcher = Some(w)
  }

}
