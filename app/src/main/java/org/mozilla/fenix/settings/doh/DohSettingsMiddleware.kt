/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings.doh

import androidx.navigation.NavController
import android.os.Handler
import android.os.Looper
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

internal class DohSettingsMiddleware(
    private val getNavController: () -> NavController,
    private val getSettingsProvider: () -> DohSettingsProvider,
    private val getAppSettings: () -> org.mozilla.fenix.utils.Settings,
    private val getEngine: () -> mozilla.components.concept.engine.Engine,
    private val openUrlInBrowser: (String) -> Unit,
    private val exitDohSettings: () -> Unit,
) : Middleware<DohSettingsState, DohSettingsAction> {
    private val debounce = Handler(Looper.getMainLooper())
    private val debounceMs = 800L

    override fun invoke(
        store: Store<DohSettingsState, DohSettingsAction>,
        next: (DohSettingsAction) -> Unit,
        action: DohSettingsAction,
    ) {
        next(action)

        when (action) {
            Init -> {
                store.dispatch(loaded(getSettingsProvider(), getAppSettings()))
                overlayTxt(store)
            }

            is BackClicked -> {
                if (!getNavController().popBackStack()) {
                    exitDohSettings()
                }
            }

            is LearnMoreClicked -> {
                openUrlInBrowser(action.url)
            }

            is DohSettingsRootAction.ExceptionsClicked -> {
                getNavController().navigate(DohSettingsDestinations.EXCEPTIONS_LIST)
            }

            is DohSettingsRootAction.DohOptionSelected -> {
                getSettingsProvider().setProtectionLevel(action.protectionLevel, action.provider)
                overlayTxt(store)
            }

            is DohSettingsRootAction.DeleteCustomClicked -> {
                val provider = getSettingsProvider()
                provider.removeCustomProvider(action.provider.url)
                store.dispatch(
                    DohSettingsRootAction.DohSettingsLoaded(
                        provider.getProtectionLevels(), provider.getSelectedProtectionLevel(),
                        provider.getDefaultProviders(), provider.getSelectedProvider(), provider.getExceptions(),
                    )
                )
            }

            is DohSettingsRootAction.DohCustomProviderDialogAction.AddCustomClicked -> {
                handleAddCustomProvider(store, action)
            }

            is DohSettingsRootAction.DohCustomProviderDialogAction.CancelClicked -> {
                val provider = getSettingsProvider()
                store.dispatch(
                    DohSettingsRootAction.DohSettingsLoaded(
                        allProtectionLevels = provider.getProtectionLevels(),
                        selectedProtectionLevel = provider.getSelectedProtectionLevel(),
                        exceptionsList = provider.getExceptions(),
                        providers = provider.getDefaultProviders(),
                        selectedProvider = provider.getSelectedProvider(),
                    )
                )
            }

            is DohSettingsRootAction.DefaultInfoClicked -> {
                getNavController().navigate(DohSettingsDestinations.INFO_DEFAULT)
            }

            is DohSettingsRootAction.IncreasedInfoClicked -> {
                getNavController().navigate(DohSettingsDestinations.INFO_INCREASED)
            }

            is DohSettingsRootAction.MaxInfoClicked -> {
                getNavController().navigate(DohSettingsDestinations.INFO_MAX)
            }

            is ExceptionsAction.AddExceptionsClicked -> {
                getNavController().navigate(DohSettingsDestinations.ADD_EXCEPTION)
            }

            is ExceptionsAction.RemoveClicked -> {
                handleRemoveException(store, action)
            }

            is ExceptionsAction.RemoveAllClicked -> {
                getSettingsProvider().setExceptions(emptyList())
                store.dispatch(ExceptionsAction.ExceptionsUpdated(emptyList()))
            }

            is ExceptionsAction.SaveClicked -> {
                handleSaveException(store, action)
            }

            is DohSettingsRootAction.DohTxtToggled -> {
                val settings = getAppSettings()
                settings.dohTxtEnabled = action.enabled
                overlayTxt(store)
            }
            is DohSettingsRootAction.DohTxtHostChanged -> {
                val settings = getAppSettings()
                settings.dohTxtHost = action.host
                settings.dohTxtEnabled = action.host.trim().isNotBlank()
                debounceOverlay(store)
            }
            is DohSettingsRootAction.DohTxtUriChanged -> {
                getAppSettings().dohTxtUri = action.uri
                debounceOverlay(store)
            }
            else -> {}
        }
    }

    private fun handleAddCustomProvider(
        store: Store<DohSettingsState, DohSettingsAction>,
        action: DohSettingsRootAction.DohCustomProviderDialogAction.AddCustomClicked,
    ) {
        try {
            val normalizedUrl = DohUrlValidator.validate(action.url)
            val provider = getSettingsProvider()
            provider.setCustomProvider(normalizedUrl)
            if (action.customProvider.url.isNotBlank() && action.customProvider.url != normalizedUrl) {
                provider.removeCustomProvider(action.customProvider.url)
            }
            store.dispatch(
                DohSettingsRootAction.DohCustomProviderDialogAction.ValidUrlDetected(
                    action.customProvider,
                    normalizedUrl,
                )
            )
        } catch (e: UrlValidationException.NonHttpsUrlException) {
            store.dispatch(DohSettingsRootAction.DohCustomProviderDialogAction.NonHttpsUrlDetected)
        } catch (e: UrlValidationException.InvalidUrlException) {
            store.dispatch(DohSettingsRootAction.DohCustomProviderDialogAction.InvalidUrlDetected)
        }
    }

    private fun handleRemoveException(
        store: Store<DohSettingsState, DohSettingsAction>,
        action: ExceptionsAction.RemoveClicked,
    ) {
        val updatedExceptions = getSettingsProvider().getExceptions().filter { it != action.url }
        getSettingsProvider().setExceptions(updatedExceptions)
        store.dispatch(ExceptionsAction.ExceptionsUpdated(updatedExceptions))
    }

    private fun handleSaveException(
        store: Store<DohSettingsState, DohSettingsAction>,
        action: ExceptionsAction.SaveClicked,
    ) {
        val url = DohUrlValidator.dropScheme(action.url)
        val currExceptions = getSettingsProvider().getExceptions()

        // If the url is already in the list, just exit AddExceptionScreen
        if (currExceptions.contains(url)) {
            store.dispatch(BackClicked)
            return
        }

        try {
            DohUrlValidator.validate("https://$url")
            val updatedExceptions = currExceptions + url
            getSettingsProvider().setExceptions(updatedExceptions)
            store.dispatch(ExceptionsAction.ExceptionsUpdated(updatedExceptions))
            store.dispatch(BackClicked)
        } catch (e: UrlValidationException.InvalidUrlException) {
            store.dispatch(ExceptionsAction.InvalidUrlDetected)
        }
    }

    private fun loaded(
        provider: DohSettingsProvider,
        settings: org.mozilla.fenix.utils.Settings,
        status: String = "",
    ) = DohSettingsRootAction.DohSettingsLoaded(
        allProtectionLevels = provider.getProtectionLevels(),
        selectedProtectionLevel = provider.getSelectedProtectionLevel(),
        exceptionsList = provider.getExceptions(),
        providers = provider.getDefaultProviders(),
        selectedProvider = provider.getSelectedProvider(),
        dohTxtEnabled = settings.dohTxtEnabled,
        dohTxtHost = if (settings.dohTxtEnabled) settings.dohTxtHost else "",
        dohTxtUri = settings.dohTxtUri,
        dohTxtStatus = status,
    )

    private fun overlayTxt(store: Store<DohSettingsState, DohSettingsAction>) {
        debounce.removeCallbacksAndMessages(null)
        DohTxtController.applyFromSettings(getAppSettings(), getEngine()) { status ->
            store.dispatch(DohSettingsRootAction.DohTxtStatus(status))
        }
    }

    private fun debounceOverlay(store: Store<DohSettingsState, DohSettingsAction>) {
        debounce.removeCallbacksAndMessages(null)
        debounce.postDelayed({ overlayTxt(store) }, debounceMs)
    }
}
