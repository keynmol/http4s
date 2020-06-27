package org.http4s.client.apachehttpclient

import java.io.{InputStream, OutputStream}

import cats.effect.implicits._
import cats.effect.{Blocker, ConcurrentEffect, ContextShift}
import org.apache.http.Header
import org.apache.http.entity.AbstractHttpEntity
import org.apache.http.message.BasicHeader
import org.http4s.Request
import org.http4s.headers.{`Content-Encoding`, `Content-Type`}

import scala.concurrent.ExecutionContext

class ApacheClientEntity[F[_]: ContextShift](req: Request[F])(implicit F: ConcurrentEffect[F])
    extends AbstractHttpEntity {
  override def isRepeatable: Boolean = false

  override def isChunked: Boolean = true

  override def getContentLength: Long = -1

  override def getContentType: Header = {
    println("content is being had (checking headers type)")

    req.headers.get(`Content-Type`) match {
      case Some(h) => new BasicHeader(h.name.value, h.value)
      case None => null
    }
  }

  override def getContentEncoding: Header = {
    println("content is being had(checking headers encoding)")

    req.headers.get(`Content-Encoding`) match {
      case Some(h) => new BasicHeader(h.name.value, h.value)
      case None => null
    }
  }

  override def getContent: InputStream = {
    println("content is being had")
    req.body.through(fs2.io.toInputStream).compile.lastOrError.toIO.unsafeRunSync()
  }

  override def writeTo(outStream: OutputStream): Unit =
    req.body
      .through(
        fs2.io.writeOutputStream(
          F.pure(outStream),
          Blocker.liftExecutionContext(ExecutionContext.global)))
      .compile
      .drain
      .toIO
      .unsafeRunSync()

  override def isStreaming: Boolean = true

  override def consumeContent(): Unit = ()
}
