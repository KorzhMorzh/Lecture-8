package ru.ifmo.backend_2021

import cask.endpoints.WsHandler
import cask.util.Ws
import ru.ifmo.backend_2021.ApplicationUtils.Document
import ru.ifmo.backend_2021.connections.{ConnectionPool, WsConnectionPool}
import ru.ifmo.backend_2021.pseudodb.{MessageDB, PseudoDB}
import scalatags.Text.all._
import scalatags.generic
import scalatags.text.Builder

object RedditApplication extends cask.MainRoutes {
  val serverUrl = s"http://$host:$port"
  val db: MessageDB = PseudoDB(s"db.txt", clean = true)
  val connectionPool: ConnectionPool = WsConnectionPool()

  @cask.staticResources("/static")
  def staticResourceRoutes() = "static"

  @cask.get("/")
  def hello(): Document = doctype("html")(
    html(
      head(
        link(rel := "stylesheet", href := ApplicationUtils.styles),
        script(src := "/static/app.js")
      ),
      body(
        div(cls := "container")(
          h1("Reddit: Swain is mad :("),
          div(id := "messageList")(messageList()),
          div(id := "errorDiv", color.red),
          form(onsubmit := "return submitForm()")(
            input(`type` := "text", id := "replyToInput", placeholder := "Reply To (Optional)"),
            input(`type` := "text", id := "nameInput", placeholder := "Username"),
            input(`type` := "text", id := "msgInput", placeholder := "Write a message!"),
            input(`type` := "submit", value := "Send"),
          ),
          form(onsubmit := "return filterForm()")(
            input(`type` := "text", id := "filterInput", placeholder := "Filter Messages"),
            input(`type` := "submit", value := "Send")
          )
        )
      )
    )
  )

  def messageList(filter: Option[String] = None): generic.Frag[Builder, String] = {
    val messages = getMessageList
    frag(for (
      (message, i) <- messages.zipWithIndex
      if filter.isDefined && message.username == filter.get || filter.isEmpty
    ) yield message.getHtmlMessage(i + 1, filter.isDefined))
  }

  private def getMessageList: List[Message] = {
    val messages = db.getMessages.groupBy(_.parentId)
    messages.getOrElse(-1, List[Message]())
      .foldLeft(List[Message]())((result: List[Message], message: Message) => {
        appendChildrenMessages(0, message.id, messages, result :+ message)
      })
  }

  private def appendChildrenMessages(depth: Int, parentId: Int, groupedMessages: Map[Int, List[Message]], result: List[Message]): List[Message] = {
    val children = groupedMessages.get(parentId)
    if (children.isDefined) {
      children.get.foldLeft(result)((result: List[Message], child: Message) => {
        appendChildrenMessages(depth + 1, child.id, groupedMessages, result :+ child)
      })
    } else {
      result
    }
  }

  @cask.websocket("/subscribe")
  def subscribe(): WsHandler = connectionPool.wsHandler { connection =>
    connectionPool.send(Ws.Text(messageList().render))(connection)
  }

  @cask.postJson("/")
  def postChatMsg(name: String, msg: String, replyTo: String = ""): ujson.Obj = {
    log.debug(name, msg)
    val replyId = replyTo.toIntOption.getOrElse(-1)
    val messages = getMessageList
    if (name == "") ujson.Obj("success" -> false, "err" -> "Name cannot be empty")
    else if (msg == "") ujson.Obj("success" -> false, "err" -> "Message cannot be empty")
    else if (name.contains("#")) ujson.Obj("success" -> false, "err" -> "Username cannot contain '#'")
    else if ((replyId <= 0 || replyId > messages.size) && replyTo != "") ujson.Obj("success" -> false, "err" -> "Incorrect message number")
    else synchronized {

      val (parentId, depth) = if (replyId != -1) {
        val parent = messages(replyId - 1)
        (parent.id, parent.depth + 1)
      } else {
        (-1, 0)
      }
      db.addMessage(Message(name, msg, messages.size, parentId, depth))
      connectionPool.sendAll(connection => Ws.Text(messageList(connectionPool.getFilter(connection)).render))
      ujson.Obj("success" -> true, "err" -> "")
    }
  }

  @cask.postJson("/messages")
  def addMessage(username: String, message: String, replyTo: Int = -1): ujson.Obj = {
    synchronized {
      postChatMsg(username, message, if(replyTo == -1) "" else replyTo.toString)
    }
  }

  @cask.get("/messages/:username")
  def getUserMessages(username: String): ujson.Obj = {
    synchronized {
      val messages = db.getMessages.filter(_.username == username).map(_.message)
      ujson.Obj("messages" -> messages)
    }
  }

  @cask.get("/messages")
  def getMessages(): ujson.Obj = {
    synchronized {
      ujson.Obj("messages" -> getMessageList.sortBy(_.id).map(message => {
        val obj = ujson.Obj("id" -> message.id, "username" -> message.username, "message" -> message.message)
        if (message.parentId != -1) obj("parentId") = message.parentId
        obj
      }))
    }
  }

  log.debug(s"Starting at $serverUrl")
  initialize()
}
