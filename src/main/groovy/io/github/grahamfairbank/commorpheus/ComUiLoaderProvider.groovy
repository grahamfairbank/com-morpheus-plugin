package io.github.grahamfairbank.commorpheus

import com.morpheusdata.core.AbstractGlobalUIComponentProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * Loads the plugin's browser script on every page, only while action buttons or route diagnostics are on.
 * Inline <script> in tab HTML can be dropped by older Morpheus UIs (lesson from builtbyfood/gfs2-rescue),
 * so the script is a plugin asset included through a global UI component instead.
 */
@Slf4j
class ComUiLoaderProvider extends AbstractGlobalUIComponentProvider {

	ComMorpheusPlugin plugin
	MorpheusContext morpheus

	ComUiLoaderProvider(ComMorpheusPlugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheus = morpheus
	}

	@Override String getCode() { 'com-morpheus-ui-loader' }
	@Override String getName() { 'com-morpheus script loader' }
	@Override Plugin getPlugin() { plugin }
	@Override MorpheusContext getMorpheus() { morpheus }

	@Override
	Boolean show(User user, Account account) {
		ComSettings s = plugin.loadSettings()
		return s.configured && (s.routeDiagnostics || s.enableActions) && ComMorpheusPlugin.canView(s, user)
	}

	@Override
	HTMLResponse renderTemplate(User user, Account account) {
		try {
			ViewModel<Map> vm = new ViewModel<>()
			vm.object = [version: plugin.version ?: 'dev']
			return getRenderer().renderTemplate('hbs/com-loader', vm)
		} catch (Throwable e) {
			log.warn("com-morpheus: script loader failed: ${e}")
			return HTMLResponse.success('')
		}
	}
}
