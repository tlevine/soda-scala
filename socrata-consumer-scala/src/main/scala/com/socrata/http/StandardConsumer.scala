package com.socrata.http

import scala.io.Codec

import com.rojoma.json.ast.JObject

import com.socrata.future.ExecutionContext

class StandardConsumer[T](bodyConsumer: Codec => BodyConsumer[T], defaultRetryAfter: Int = 60)(implicit execContext: ExecutionContext) extends StatusConsumer[Retryable[T]] {
  def apply(status: Status): Either[HeadersConsumer[Retryable[T]], Retryable[T]] = {
    if(status.isSuccess) success(status)
    else if(status.isRedirect) redirect(status)
    else if(status.isClientError) clientError(status)
    else if(status.isServerError) serverError(status)
    else // throw something
      error("NYI")
  }

  private def success(status: Status): Either[HeadersConsumer[Retryable[T]], Retryable[T]] = {
    // 200: normal, just proceed with upload
    // 202: 202 handling
    // 203: same as 200
    // 204: almost the same as 200 but after header-processing call the bodyConsumer with an empty array and isLast=true
    // 205: same as 204
    // 206: will not be generated by SODA2
    status.code match {
      case 200 => Left(new OKHeadersConsumer(bodyConsumer))
      case 202 => Left(new AcceptedHeadersConsumer(defaultRetryAfter))
    }
  }

  private def redirect(status: Status) = {
    // 300: will not be generated by SODA2
    // 301, 302, 307: if this was a GET, redirect.  Otherwise fail with "unexepected redirect".
    // 303: redirect
    // 304: we will never send a conditional GET and therefore this will never be sent back
    // 305, 306: we will never receive this from a SODA2 server
    error("NYI")
  }

  private def clientError(status: Status) = {
    // fail with appropriate error
    error("NYI")
  }

  private def serverError(status: Status) = {
    // fail with appropriate error
    error("NYI")
  }
}

class WrappedBodyConsumer[T](underlying: BodyConsumer[T]) extends BodyConsumer[Retryable[T]] {
  def apply(bytes: Array[Byte], isLast: Boolean) =
    underlying(bytes, isLast) match {
      case Left(bc) =>
        Left(new WrappedBodyConsumer(bc))
      case Right(v) =>
        Right(Right(v))
    }
}

sealed abstract class NewRequest {
  def retryAfter: Int
  def details: JObject
}
case class Retry(retryAfter: Int, details: JObject) extends NewRequest
case class Redirect(newUrl: String, retryAfter: Int,  details: JObject) extends NewRequest
case class RetryWithTicket(ticket: String, retryAfter: Int, details: JObject) extends NewRequest