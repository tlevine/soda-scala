package com.socrata.future

object Future {
  def apply[A](a: =>A)(implicit execService: ExecutionContext): Future[A] = {
    execService.execute(a)
  }

  def now[A](value: =>A): Future[A] = try {
    new SimpleValueFuture(value)
  } catch {
    case e: Throwable =>
      new SimpleFailureFuture(e)
  }
}

trait Future[+A] {
  def onComplete[U](f: Either[Throwable, A] => U)
  def result(): Option[A]
  def await()

  def onSuccess[U](f: A => U) = onComplete {
    case Right(a) => f(a)
    case _ => // nothing
  }

  def onFailure[U](f: Throwable => U) = onComplete {
    case Left(e) => f(e)
    case _ => // nothing
  }

  def flatten[B](implicit ev: A => Future[B]): Future[B] = flatMap { x => x }

  def flatMap[B](f: A => Future[B]): Future[B] = {
    val promise = new Promise[B]

    onComplete {
      case Left(err) =>
        promise.break(err)
      case Right(interim) =>
        var good = true
        val newFuture = try {
          f(interim)
        } catch {
          case e: Throwable =>
            promise.break(e)
            good = false
            null
        }
        if(good) {
          newFuture.onComplete {
            case Left(err) => promise.break(err)
            case Right(value) => promise.fulfill(value)
          }
        }
    }

    promise.future
  }

  def map[B](f: A => B): Future[B] = {
    val promise = new Promise[B]

    def complete(x: Either[Throwable, A]) {
      x match {
        case Left(err) =>
          promise.break(err)
        case Right(interim) =>
          val result = try {
            f(interim)
          } catch {
            case e: Throwable =>
              promise.break(e)
              return
          }
          promise.fulfill(result)
      }
    }

    onComplete(complete)

    promise.future
  }

  def filter(f: A => Boolean): Future[A] = map { a =>
    if(f(a)) a
    else throw new NoSuchElementException("Excluded by filter")
  }

  def withFilter(f: A => Boolean): Future[A] = filter(f)

  def apply(): A = {
    result() match {
      case Some(r) => r
      case None => await(); apply()
    }
  }
}