package io.github.grahamfairbank.commorpheus

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.PluginManager
import spock.lang.IgnoreIf
import spock.lang.Specification

/**
 * Opt-in: LOAD_JARS=/path/a.jar,/path/b.jar loads each built plugin jar through morpheus-plugin-api's own
 * PluginManager.registerPlugin(path), the same entry point VME uses, and reports whether it loads and,
 * if not, why. registerPlugin swallows the exception and prints it, so stdout and stderr are captured too.
 * Only jars whose classes are NOT on the test classpath give a valid result (registerPlugin's class loader is
 * parent-first): test with renamed builds, not com-morpheus ones.
 */
class PluginLoadSpec extends Specification {

	@IgnoreIf({ !System.getenv('LOAD_JARS') })
	def "load each jar through PluginManager"() {
		given:
		List<String> jars = System.getenv('LOAD_JARS').split(',')*.trim().findAll()
		List<String> report = []

		when:
		jars.each { String path ->
			PluginManager pm = new PluginManager(Mock(MorpheusContext))
			ByteArrayOutputStream buf = new ByteArrayOutputStream()
			PrintStream old = System.out
			PrintStream oldErr = System.err
			Plugin p = null
			Throwable thrown = null
			System.setOut(new PrintStream(buf, true))
			System.setErr(new PrintStream(buf, true))
			try {
				p = pm.registerPlugin(path)
			} catch (Throwable e) {
				thrown = e
			} finally {
				System.setOut(old)
				System.setErr(oldErr)
			}
			String out = buf.toString().readLines().take(40).join('\n')
			report << "== ${new File(path).name}: ${p ? "LOADED ${p.code} ${p.version}, controllers=${p.controllers.size()}, customRender=${p.hasCustomRender()}" : 'FAILED'}" +
				(thrown ? "\n  threw: ${thrown}" : '') + (p ? '' : "\n${out}")
		}
		new File(System.getenv('LOAD_REPORT') ?: 'build/plugin-load-report.txt').text = report.join('\n') + '\n'

		then:
		true
	}
}
