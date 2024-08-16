package to.bitkit.ext

val Thread.callerName: String
    get() {
        // [ getThreadStackTrace, getStackTrace, this@getCallerName, background, actual caller]
        val element = stackTrace[4]
        val classSimpleName = element.className.substringAfterLast('.')
        val methodName = element.methodName
        return "$classSimpleName.$methodName"
    }
