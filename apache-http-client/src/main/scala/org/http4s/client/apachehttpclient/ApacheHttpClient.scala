/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s

package client
package apachehttpclient

import java.net.URI

import cats.ApplicativeError
import cats.effect._
import cats.syntax.all._
import org.apache.http.client.methods.{
  CloseableHttpResponse,
  HttpEntityEnclosingRequestBase,
  HttpRequestBase
}
import org.apache.http.impl.client.{CloseableHttpClient, HttpClients}
import org.apache.http.{HttpVersion => JHttpVersion}
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.dsl.Http4sDsl
import org.log4s.{Logger, getLogger}

object ApacheHttpClient extends App {

  private val logger: Logger = getLogger

  import org.apache.http.impl.conn.PoolingHttpClientConnectionManager

  val cm = new PoolingHttpClientConnectionManager // Increase max total connection to 200
  cm.setMaxTotal(200); // Increase default max connection per route to 20
  cm.setDefaultMaxPerRoute(20);

  def allocate[F[_]: ContextShift](client: CloseableHttpClient)(implicit
      M: ConcurrentEffect[F]): F[(Client[F], F[Unit])] = {
    val acquire = M.pure(client).map { apacheClient =>
      Client[F] { req =>
        val modifiedReq =
          req.withHeaders(req.headers.filterNot(_.name.toString.toLowerCase == "content-length"))
        val apacheRequest = toApacheRequest(modifiedReq)

        val acquireResponse = apacheRequest.flatMap(r =>
          M.delay {
            val resp = apacheClient.execute(r)

            Option(resp.getEntity) match {
              case None => println(s"No entity for $req"); resp
              case Some(e) =>
                println(s"Received $resp to $req: $e")
                M.toIO(fs2.io
                  .readInputStream(
                    M.pure(e.getContent()),
                    4096,
                    Blocker.liftExecutionContext(scala.concurrent.ExecutionContext.global))
                  .through(fs2.text.utf8Decode)
                  .chunks
                  .evalMap(chunk => M.delay(println(chunk)))
                  .compile
                  .drain).unsafeRunSync()
                resp
            }
          })

        val disposeResponse = acquireResponse.map(resp => resp.close())
        Resource.make[F, Response[F]](acquireResponse.flatMap { apacheResponse =>
          toResponse(apacheResponse)
        })(_ => disposeResponse)
      }
    }

    val dispose = M
      .delay(client.close())
      .handleErrorWith(t => M.delay(logger.error(t)("Unable to shut down Apache Http client")))

    acquire.map(_ -> dispose)
  }

  def toResponse[F[_]](resp: CloseableHttpResponse)(implicit
      F: ApplicativeError[F, Throwable]): F[Response[F]] = {
    val statusO = F.fromEither(Status.fromInt(resp.getStatusLine.getStatusCode))

    val rawHeaders = resp.getAllHeaders.map { h =>
      Header.apply(h.getName, h.getValue).parsed
    }.toSeq

    val newHeaders = Headers.of(rawHeaders: _*)

    val version = resp.getProtocolVersion match {
      case JHttpVersion.HTTP_1_0 => HttpVersion.`HTTP/1.0`
      case JHttpVersion.HTTP_1_1 => HttpVersion.`HTTP/1.1`
      case _ => HttpVersion.`HTTP/1.0`

    }

    statusO.map(s => Response(s, headers = newHeaders, httpVersion = version).withEmptyBody)
  }

  def resource[F[_]: ContextShift](client: CloseableHttpClient = HttpClients.createDefault())(
      implicit F: ConcurrentEffect[F]): Resource[F, Client[F]] =
    Resource(allocate[F](client))

  def stream[F[_]: ContextShift](client: CloseableHttpClient = HttpClients.createDefault())(implicit
      F: ConcurrentEffect[F]): fs2.Stream[F, Client[F]] =
    fs2.Stream.resource(resource(client))

  private def toApacheRequest[F[_]: ContextShift](
      request: Request[F]
  )(implicit F: ConcurrentEffect[F]): F[HttpRequestBase] = {
    val b = new HttpEntityEnclosingRequestBase {
      override def getMethod: String = request.method.name
    }

    b.setEntity(new ApacheClientEntity(request))

    b.setURI(URI.create(request.uri.renderString))

    request.headers.foreach { header =>
      b.addHeader(header.name.toString, header.value)
    }

    val version: F[JHttpVersion] = request.httpVersion match {
      case HttpVersion.`HTTP/1.0` => F.pure(JHttpVersion.HTTP_1_0)
      case HttpVersion.`HTTP/1.1` => F.pure(JHttpVersion.HTTP_1_1)
      case _ =>
        F.raiseError(
          new RuntimeException(
            s"Apache HTTP Client does not support HTTP version ${request.httpVersion}"))
    }

    version.map { ver =>
      b.setProtocolVersion(ver)

      b
    }
  }

  private def test = {
    val dsl = new Http4sDsl[IO] with Http4sClientDsl[IO] {}
    import dsl._

    val uri = Uri.unsafeFromString("https://jsonplaceholder.typicode.com/posts")

//    val js = "{}"

    val req = GET(uri) //.map(_.withEntity(js))

    println(req.unsafeRunSync())

    implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)

    val clientRes = resource[IO]()

    clientRes
      .flatMap { client =>
        Resource.liftF(req).flatMap(client.run)
      }
      .use(response => response.as[String])
      .unsafeRunSync
  }

  test

}
