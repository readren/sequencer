package readren.taskflow

final class Maybe[+A](private val value: Any) extends AnyVal {

	inline def isEmpty: Boolean = value ==  null

	inline def isDefined: Boolean = value != null

	inline def get: A =
		if isEmpty then throw new NoSuchElementException("FastOption.get on empty FastOption")
		else value.asInstanceOf[A]

	inline def map[B](f: A => B): Maybe[B] =
		if isEmpty then Maybe.empty else Maybe.some(f(get))

	inline def flatMap[B](f: A => Maybe[B]): Maybe[B] =
		if isEmpty then Maybe.empty else f(value.asInstanceOf[A])

	inline def fold[B](inline ifEmpty: => B)(inline f: A => B): B =
		if isEmpty then ifEmpty else f(value.asInstanceOf[A])

	inline def getOrElse[B >: A](default: B): B =
		if isEmpty then default else value.asInstanceOf[A]
}

object Maybe {

	val empty: Maybe[Nothing] = new Maybe(null)

	inline def apply[A](a: A): Maybe[A] =
		if a == null then empty else new Maybe(a)

	inline def some[A](a: A): Maybe[A] =
		if a == null then throw new IllegalArgumentException("Maybe.some cannot wrap null")
		else new Maybe(a)

	def liftPartialFunction[A, B](pf: PartialFunction[A, B]): A => Maybe[B] =
		(a: A) => if pf.isDefinedAt(a) then some(pf.apply(a)) else empty
}