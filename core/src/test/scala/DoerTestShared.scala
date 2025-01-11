package readren.taskflow

import DoerTestShared.currentAssistant

import org.scalacheck.{Arbitrary, Gen}

import java.util.concurrent.{ExecutorService, Executors}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

object DoerTestShared {
	val currentAssistant: ThreadLocal[Doer.Assistant] = new ThreadLocal()
}

/** Contains tools used by the suites that test [[Doer]] behavior. */
class DoerTestShared[TD <: Doer](val doer: TD, synchronousOnly: Boolean = false) {

	import doer.*

	val foreignDoer: Doer = {
		val foreignAssistant: Doer.Assistant = new Doer.Assistant { thisAssistant => 
			private val executor = Executors.newSingleThreadExecutor()

			override def queueForSequentialExecution(runnable: Runnable): Unit = executor.execute { () =>
				currentAssistant.set(thisAssistant)
				try runnable.run()
				finally currentAssistant.remove()
			}

			override def current: Doer.Assistant = currentAssistant.get

			override def reportFailure(cause: Throwable): Unit = throw cause
		}
		new Doer {
			override val assistant: Doer.Assistant = foreignAssistant
		}
	}

	extension [A](genA: Gen[A]) {
		def toTry(failureLabel: String): Gen[Try[A]] = Gen.frequency(
			(5, genA.map(a => Success(a))),
			(1, genA.map(a => Failure(new RuntimeException(s"$failureLabel: $a"))))
			)

		def toFutureBuilder(failureLabel: String): Gen[() => Future[A]] = {
			val immediateGen: Gen[() => Future[A]] = genA.toTry(s"$failureLabel / FutureBuilder.immediate").map(tryA => () => Future.fromTry(tryA))
			val delayedGen: Gen[() => Future[A]] =
				for {
					tryA <- genA.toTry(s"$failureLabel / FutureBuilder.delayed")
					delay <- Gen.oneOf(0, 1, 2, 4, 8, 16)
				} yield {
					() => {
						Future(Thread.sleep(delay))(ExecutionContext.global)
							.transform(_ => tryA)(ExecutionContext.global)
					}
				}
			Gen.oneOf(immediateGen, delayedGen)
		}

		def toFuture(failureLabel: String): Gen[Future[A]] = {
			genA.toFutureBuilder(s"$failureLabel / FutureInstance").map(builder => builder())
		}

		def toDuty: Gen[Duty[A]] = {
			val readyGen: Gen[Duty[A]] = genA.map(Duty.ready)

			val mineGen: Gen[Duty[A]] = genA.map { a => Duty.mine(() => a) }

			val ownFlatGen: Gen[Duty[A]] = Gen.oneOf(readyGen, mineGen).map(da => Duty.mineFlat(() => da))

			val foreignGen: Gen[Duty[A]] = for {a <- genA; delay <- Gen.oneOf(0, 1, 2, 4, 8, 16)} yield
				Task.alien(() => Future {
					Thread.sleep(delay);
					a
				}(ExecutionContext.global)).map(_.asInstanceOf[Success[A]].value)

			if synchronousOnly then Gen.oneOf(readyGen, mineGen, ownFlatGen)
			else Gen.oneOf(readyGen, mineGen, ownFlatGen, foreignGen)
		}

		def toTask(failureLabel: String): Gen[Task[A]] = {

			val immediateGen: Gen[Task[A]] = genA.toTry(s"$failureLabel / Task.immediate").map(Task.ready)

			val ownGen: Gen[Task[A]] = genA.toTry(s"$failureLabel / Task.own").map { tryA => Task.own(() => tryA) }

			val ownFlatGen: Gen[Task[A]] = Gen.oneOf(immediateGen, ownGen).map { taskA => Task.ownFlat(() => taskA) }

			val waitGen: Gen[Task[A]] = genA.toFuture(s"$failureLabel / Task.wait").map(Task.wait)

			val alienGen: Gen[Task[A]] = genA.toFutureBuilder(s"$failureLabel / Task.alien").map(Task.alien)

			val foreignGen: Gen[Task[A]] = genA.toFutureBuilder(s"$failureLabel / Task.foreign").map {
				futureBuilder => Task.foreign(foreignDoer)(foreignDoer.Task.alien(futureBuilder))
			}

			if synchronousOnly then Gen.oneOf(immediateGen, ownGen, ownFlatGen)
			else Gen.oneOf(immediateGen, ownGen, ownFlatGen, waitGen, alienGen, foreignGen)
		}
	}

	given futureArbitrary[A](using arbA: Arbitrary[A]): Arbitrary[Future[A]] = Arbitrary {
		arbA.arbitrary.toFuture("futureArbitrary")
	}


	// Implicitly provide an Arbitrary instance for Task
	given taskArbitrary[A](using arbA: Arbitrary[A]): Arbitrary[Task[A]] = Arbitrary {
		arbA.arbitrary.toTask("taskArbitrary")
	}

	given dutyArbitrary[A](using arbA: Arbitrary[A]): Arbitrary[Duty[A]] = Arbitrary {
		arbA.arbitrary.toDuty
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

	//	extension [A](thisTask: Task[A]) {
	//		def ====(otherTask: Task[A]): Task[Boolean] = {
	//			Task.combine(thisTask, otherTask)((a, b) => Success(a ==== b))
	//		}
	//	}

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
