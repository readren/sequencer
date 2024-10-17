package readren.taskflow

import org.scalacheck.{Arbitrary, Gen}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/** Contains tools used by the suites that test [[TaskDomain]] behavior. */
class TaskDomainTestShared[TD <: TaskDomain](val taskDomain: TD, synchronousOnly: Boolean = false) {

	import taskDomain.*

	extension [A](genA: Gen[A]) {
		def toTry(failureLabel: String): Gen[Try[A]] = Gen.frequency(
			(5, genA.map(a => Success(a))),
			(1, genA.map(a => Failure(new RuntimeException(s"$failureLabel: $a"))))
		)

		def toFutureBuilder(failureLabel: String): Gen[() => Future[A]] = {
			val immediateGen: Gen[() => Future[A]] = genA.toTry(s"$failureLabel / FutureBuilder.immediate").map(Future.fromTry).map(future => () => future)
			val delayedGen: Gen[() => Future[A]] =
				for {
					tryA <- genA.toTry(s"$failureLabel / FutureBuilder.delayed")
					delay <- Gen.oneOf(0, 1, 2, 4, 8, 16)
				} yield {
					() => {
						val promise = Promise[A]()
						Future {
							Thread.sleep(delay)
							promise.complete(tryA)
						}(ExecutionContext.global)
						promise.future
					}
				}
			Gen.oneOf(immediateGen, delayedGen)
		}

		def toFuture(failureLabel: String): Gen[Future[A]] = {
			genA.toFutureBuilder(s"$failureLabel / FutureInstance").map(builder => builder())
		}

		def toTask(failureLabel: String): Gen[Task[A]] = {

			val immediateGen: Gen[Task[A]] = genA.toTry(s"$failureLabel / Task.immediate").map(Task.immediate)

			val ownGen: Gen[Task[A]] = genA.toTry(s"$failureLabel / Task.own").map { tryA => Task.own(() => tryA) }

			val waitGen: Gen[Task[A]] = genA.toFuture(s"$failureLabel / Task.wait").map(Task.wait)

			val alienGen: Gen[Task[A]] = genA.toFutureBuilder(s"$failureLabel / Task.alien").map(Task.alien)

			if synchronousOnly then Gen.oneOf(immediateGen, ownGen)
			else Gen.oneOf(immediateGen, ownGen, waitGen, alienGen)
		}
	}

	given futureArbitrary[A](using arbA: Arbitrary[A]): Arbitrary[Future[A]] = Arbitrary {
		arbA.arbitrary.toFuture("futureArbitrary")
	}


	// Implicitly provide an Arbitrary instance for Task
	given taskArbitrary[A](using arbA: Arbitrary[A]): Arbitrary[Task[A]] = Arbitrary {
		arbA.arbitrary.toTask("taskArbitrary")
	}

	given intGen: Gen[Int] = Gen.choose(-10, 10)

	extension (e1: Throwable) {
		def ====(e2: Throwable): Boolean = e1.getClass == e2.getClass && e1.getMessage == e2.getMessage
	}

	extension [A](try1: Try[A]) {
		def ====(try2: Try[A]): Boolean = {
			(try1, try2) match {
				case (Failure(e1), Failure(e2)) => e1 ==== e2
				case (Success(v1), Success(v2)) => v1 == v2
				case _ => false
			}
		}
	}

	extension [A](thisTask: Task[A]) {
		def ====(otherTask: Task[A]): Task[Boolean] = {
			Task.combine(thisTask, otherTask)((a, b) => Success(a ==== b))
		}
	}

	given throwableArbitrary: Arbitrary[Throwable] = Arbitrary {
		for {
			msg <- Gen.stringOfN(9, Gen.alphaChar)
			ex <- Gen.oneOf(
				Gen.const(new RuntimeException(s"Runtime: [$msg]")),
				Gen.const(new IllegalArgumentException(s"Illegal argument: [$msg]")),
				Gen.const(new UnsupportedOperationException(s"Unsupported operation: [$msg]")),
				Gen.const(new Exception(s"General exception: [$msg]", new RuntimeException("Cause exception"))),
				Gen.const(new InternalError(s"InternalError: [$msg]")),
				Gen.const(new LinkageError(s"LinkageError: [$msg]")),
				Gen.const(new Error(s"Error: [$msg]"))
			)
		} yield ex
	}
}
