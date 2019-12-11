package org.http4s
package client
package apachehttpclient

import cats.effect.IO
import org.apache.http.impl.client.HttpClients

class ApacheHttpClientSpec extends ClientRouteTestBattery("ApacheHttpClient") {
  override def clientResource = {
    import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
    val cm = new PoolingHttpClientConnectionManager
    val base = HttpClients.custom.setConnectionManager(cm).build()
    ApacheHttpClient.resource[IO](base)
  }
    //Resource.liftF(IO.raiseError[Client[IO]](new RuntimeException("no")))
}
