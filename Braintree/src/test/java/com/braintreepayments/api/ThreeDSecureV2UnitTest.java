package com.braintreepayments.api;

import android.content.Intent;

import com.braintreepayments.api.interfaces.HttpResponseCallback;
import com.braintreepayments.api.internal.ManifestValidator;
import com.braintreepayments.api.models.Authorization;
import com.braintreepayments.api.models.CardBuilder;
import com.braintreepayments.api.models.CardNonce;
import com.braintreepayments.api.models.Configuration;
import com.braintreepayments.api.models.PaymentMethodNonce;
import com.braintreepayments.api.models.ThreeDSecureLookup;
import com.braintreepayments.api.models.ThreeDSecurePostalAddress;
import com.braintreepayments.api.models.ThreeDSecureRequest;
import com.braintreepayments.testutils.TestConfigurationBuilder;
import com.cardinalcommerce.cardinalmobilesdk.Cardinal;
import com.cardinalcommerce.cardinalmobilesdk.models.response.CardinalActionCode;
import com.cardinalcommerce.cardinalmobilesdk.models.response.ValidateResponse;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.rule.PowerMockRule;
import org.robolectric.RobolectricTestRunner;

import static android.app.Activity.RESULT_OK;
import static com.braintreepayments.api.BraintreePowerMockHelper.*;
import static com.braintreepayments.api.BraintreePowerMockHelper.MockManifestValidator.mockUrlSchemeDeclaredInAndroidManifest;
import static com.braintreepayments.testutils.FixturesHelper.stringFromFixture;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.verifyStatic;
import static org.powermock.api.mockito.PowerMockito.when;

@RunWith(RobolectricTestRunner.class)
@PowerMockIgnore({ "org.mockito.*", "org.robolectric.*", "android.*", "org.json.*", "javax.crypto.*" })
@PrepareForTest({ Cardinal.class, ManifestValidator.class, TokenizationClient.class })
public class ThreeDSecureV2UnitTest {
    @Rule
    public PowerMockRule mPowerMockRule = new PowerMockRule();

    private MockFragmentBuilder mMockFragmentBuilder;
    private BraintreeFragment mFragment;
    public ThreeDSecureRequest mBasicRequest;

    @Before
    public void setup() throws Exception {
        mockUrlSchemeDeclaredInAndroidManifest(true);

        Configuration configuration = new TestConfigurationBuilder()
                .threeDSecureEnabled(true)
                .buildConfiguration();

        mMockFragmentBuilder = new MockFragmentBuilder()
                .authorization(Authorization.fromString(stringFromFixture("base_64_client_token.txt")))
                .configuration(configuration);
        mFragment = mMockFragmentBuilder.build();

        mBasicRequest = new ThreeDSecureRequest()
                .nonce("a-nonce")
                .amount("1.00")
                .versionRequested(2);
    }

    @Test
    public void performVerification_withCardBuilder_tokenizesAndPerformsVerification() {
        CardNonce cardNonce = mock(CardNonce.class);
        when(cardNonce.getNonce()).thenReturn("card-nonce");
        MockStaticTokenizationClient.mockTokenizeSuccess(cardNonce);

        MockStaticCardinal.initCompletesSuccessfully("fake-df");
        ThreeDSecure.configureCardinal(mFragment);

        CardBuilder cardBuilder = new CardBuilder();
        ThreeDSecureRequest request = new ThreeDSecureRequest()
                .amount("10");

        ThreeDSecure.performVerification(mFragment, cardBuilder, request);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

        verifyStatic();
        TokenizationClient.versionedPath(captor.capture());

        assertTrue(captor.getValue().contains("card-nonce"));
    }

    @Test
    public void configureCardinal_whenSetupCompleted_sendsAnalyticEvent() {
        MockStaticCardinal.initCompletesSuccessfully("df-reference-id");

        ThreeDSecure.configureCardinal(mFragment);

        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.cardinal-sdk.init.setup-completed"));
    }

    @Test
    public void configureCardinal_whenSetupFailed_sendsAnalyticEvent() {
        MockStaticCardinal.initCallsOnValidated();

        ThreeDSecure.configureCardinal(mFragment);

        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.cardinal-sdk.init.setup-failed"));
    }
    @Test
    public void performVerification_sendsAllParamatersInLookupRequest() throws JSONException {
        MockStaticCardinal.initCompletesSuccessfully("fake-df");

        ThreeDSecureRequest request = new ThreeDSecureRequest()
                .nonce("a-nonce")
                .amount("1.00")
                .versionRequested(2)
                .shippingMethod("01")
                .mobilePhoneNumber("8101234567")
                .email("test@example.com")
                .billingAddress(new ThreeDSecurePostalAddress()
                        .firstName("Joe")
                        .lastName("Guy")
                        .streetAddress("555 Smith Street")
                        .extendedAddress("#5")
                        .locality("Oakland")
                        .region("CA")
                        .postalCode("12345")
                        .countryCodeAlpha2("US")
                        .phoneNumber("12345678"));

        ThreeDSecure.configureCardinal(mFragment);
        ThreeDSecure.performVerification(mFragment, request);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mFragment.getHttpClient()).post(anyString(), captor.capture(), any(HttpResponseCallback.class));

        JSONObject body = new JSONObject(captor.getValue());

        assertEquals("1.00", body.getString("amount"));
        assertEquals("fake-df", body.getString("df_reference_id"));

        JSONObject jsonAdditionalInformation = body.getJSONObject("additionalInformation");

        assertEquals("8101234567", jsonAdditionalInformation.getString("mobilePhoneNumber"));
        assertEquals("test@example.com", jsonAdditionalInformation.getString("email"));
        assertEquals("01", jsonAdditionalInformation.getString("shippingMethod"));
        assertEquals("Joe", jsonAdditionalInformation.getString("firstName"));
        assertEquals("Guy", jsonAdditionalInformation.getString("lastName"));
        assertEquals("12345678", jsonAdditionalInformation.getString("phoneNumber"));

        JSONObject jsonBillingAddress = jsonAdditionalInformation.getJSONObject("billingAddress");

        assertEquals("555 Smith Street", jsonBillingAddress.getString("line1"));
        assertEquals("#5", jsonBillingAddress.getString("line2"));
        assertEquals("Oakland", jsonBillingAddress.getString("city"));
        assertEquals("CA", jsonBillingAddress.getString("state"));
        assertEquals("12345", jsonBillingAddress.getString("postalCode"));
        assertEquals("US", jsonBillingAddress.getString("countryCode"));
    }

    @Test
    public void performVerification_sendsMinimumParamatersInLookupRequest() throws JSONException {
        MockStaticCardinal.initCompletesSuccessfully("fake-df");

        ThreeDSecure.configureCardinal(mFragment);
        ThreeDSecure.performVerification(mFragment, mBasicRequest);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mFragment.getHttpClient()).post(anyString(), captor.capture(), any(HttpResponseCallback.class));

        JSONObject body = new JSONObject(captor.getValue());

        assertEquals("1.00", body.getString("amount"));
        assertEquals("fake-df", body.getString("df_reference_id"));

        JSONObject jsonAdditionalInformation = body.getJSONObject("additionalInformation");

        assertTrue(jsonAdditionalInformation.isNull("mobilePhoneNumber"));
        assertTrue(jsonAdditionalInformation.isNull("email"));
        assertTrue(jsonAdditionalInformation.isNull("shippingMethod"));
        assertTrue(jsonAdditionalInformation.isNull("billingAddress"));
    }
    @Test
    public void performVerification_sendsPartialParamatersInLookupRequest() throws JSONException {
        MockStaticCardinal.initCompletesSuccessfully("fake-df");

        ThreeDSecureRequest request = new ThreeDSecureRequest()
                .nonce("a-nonce")
                .amount("1.00")
                .email("test@example.com")
                .versionRequested(2)
                .billingAddress(new ThreeDSecurePostalAddress()
                        .firstName("Joe")
                        .lastName("Guy")
                        .streetAddress("555 Smith Street")
                        .locality("Oakland")
                        .region("CA")
                        .postalCode("12345")
                        .countryCodeAlpha2("US"));

        ThreeDSecure.configureCardinal(mFragment);
        ThreeDSecure.performVerification(mFragment, request);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mFragment.getHttpClient()).post(anyString(), captor.capture(), any(HttpResponseCallback.class));

        JSONObject body = new JSONObject(captor.getValue());

        assertEquals("1.00", body.getString("amount"));
        assertEquals("fake-df", body.getString("df_reference_id"));

        JSONObject jsonAdditionalInformation = body.getJSONObject("additionalInformation");

        assertTrue(jsonAdditionalInformation.isNull("mobilePhoneNumber"));
        assertEquals("test@example.com", jsonAdditionalInformation.getString("email"));
        assertTrue(jsonAdditionalInformation.isNull("shippingMethod"));
        assertEquals("Joe", jsonAdditionalInformation.getString("firstName"));
        assertEquals("Guy", jsonAdditionalInformation.getString("lastName"));
        assertTrue(jsonAdditionalInformation.isNull("phoneNumber"));

        JSONObject jsonBillingAddress = jsonAdditionalInformation.getJSONObject("billingAddress");

        assertEquals("555 Smith Street", jsonBillingAddress.getString("line1"));
        assertTrue(jsonBillingAddress.isNull("line2"));
        assertEquals("Oakland", jsonBillingAddress.getString("city"));
        assertEquals("CA", jsonBillingAddress.getString("state"));
        assertEquals("12345", jsonBillingAddress.getString("postalCode"));
        assertEquals("US", jsonBillingAddress.getString("countryCode"));
    }

    @Test
    public void performVerification_whenAuthenticatingWithCardinal_sendsAnalyticsEvent() {
        MockStaticCardinal.initCompletesSuccessfully("reference-id");

        mMockFragmentBuilder.successResponse(stringFromFixture("three_d_secure/lookup_response_with_version_number2.json"));
        mFragment = mMockFragmentBuilder.build();

        ThreeDSecure.performVerification(mFragment, mBasicRequest);

        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.started"));
    }

    @Test
    public void performVerification_whenChallengeIsPresented_sendsAnalyticsEvent() {
        MockStaticCardinal.initCompletesSuccessfully("reference-id");

        mMockFragmentBuilder.successResponse(stringFromFixture("three_d_secure/lookup_response.json"));
        mFragment = mMockFragmentBuilder.build();

        ThreeDSecure.performVerification(mFragment, mBasicRequest);

        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.challenge-presented.true"));
    }

    @Test
    public void performVerification_whenChallengeIsNotPresented_sendsAnalyticsEvent() {
        MockStaticCardinal.initCompletesSuccessfully("reference-id");

        mMockFragmentBuilder.successResponse(stringFromFixture("three_d_secure/lookup_response_noAcsUrl.json"));
        mFragment = mMockFragmentBuilder.build();

        ThreeDSecure.performVerification(mFragment, mBasicRequest);

        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.challenge-presented.false"));
    }
    @Test
    public void performVerification_when3DSVersionIsVersion2_sendsAnalyticsEvent() {
        MockStaticCardinal.initCompletesSuccessfully("reference-id");

        mMockFragmentBuilder.successResponse(stringFromFixture("three_d_secure/lookup_response_with_version_number2.json"));
        mFragment = mMockFragmentBuilder.build();

        ThreeDSecure.performVerification(mFragment, mBasicRequest);

        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.3ds-version.2.1.0"));
    }

    @Test
    public void onActivityResult_whenCardinalCardVerificationReportsSuccess_sendsAnalyticsEvent() throws JSONException {
        mFragment = mMockFragmentBuilder.build();

        ThreeDSecureLookup threeDSecureLookup = ThreeDSecureLookup.fromJson(stringFromFixture("three_d_secure/lookup_response_with_version_number2.json"));

        ValidateResponse validateResponse = mock(ValidateResponse.class);
        when(validateResponse.getActionCode()).thenReturn(CardinalActionCode.SUCCESS);

        Intent data = new Intent();
        data.putExtra(ThreeDSecureActivity.EXTRA_VALIDATION_RESPONSE, validateResponse);
        data.putExtra(ThreeDSecureActivity.EXTRA_THREE_D_SECURE_LOOKUP, threeDSecureLookup);

        ThreeDSecure.onActivityResult(mFragment, RESULT_OK, data);

        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.completed"));
        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.cardinal-sdk.action-code.success"));
    }

    @Test
    public void onActivityResult_whenCardinalCardVerificationReportsNoAction_sendsAnalyticsEvent() throws JSONException {
        mFragment = mMockFragmentBuilder.build();

        ThreeDSecureLookup threeDSecureLookup = ThreeDSecureLookup.fromJson(stringFromFixture("three_d_secure/lookup_response_with_version_number2.json"));

        ValidateResponse validateResponse = mock(ValidateResponse.class);
        when(validateResponse.getActionCode()).thenReturn(CardinalActionCode.NOACTION);

        Intent data = new Intent();
        data.putExtra(ThreeDSecureActivity.EXTRA_VALIDATION_RESPONSE, validateResponse);
        data.putExtra(ThreeDSecureActivity.EXTRA_THREE_D_SECURE_LOOKUP, threeDSecureLookup);

        ThreeDSecure.onActivityResult(mFragment, RESULT_OK, data);

        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.completed"));
        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.cardinal-sdk.action-code.noaction"));
    }

    @Test
    public void onActivityResult_whenCardinalCardVerificationReportsFailure_sendsAnalyticsEvent() throws JSONException {
        mFragment = mMockFragmentBuilder.build();

        ThreeDSecureLookup threeDSecureLookup = ThreeDSecureLookup.fromJson(stringFromFixture("three_d_secure/lookup_response_with_version_number2.json"));

        ValidateResponse validateResponse = mock(ValidateResponse.class);
        when(validateResponse.getActionCode()).thenReturn(CardinalActionCode.FAILURE);

        Intent data = new Intent();
        data.putExtra(ThreeDSecureActivity.EXTRA_VALIDATION_RESPONSE, validateResponse);
        data.putExtra(ThreeDSecureActivity.EXTRA_THREE_D_SECURE_LOOKUP, threeDSecureLookup);

        ThreeDSecure.onActivityResult(mFragment, RESULT_OK, data);

        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.completed"));
        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.cardinal-sdk.action-code.failure"));
    }

    @Test
    public void onActivityResult_whenCardinalCardVerificationIsCanceled_sendsAnalyticsEvent() {
        mFragment = mMockFragmentBuilder.build();

        ValidateResponse validateResponse = mock(ValidateResponse.class);
        when(validateResponse.getActionCode()).thenReturn(CardinalActionCode.CANCEL);

        Intent data = new Intent();
        data.putExtra(ThreeDSecureActivity.EXTRA_VALIDATION_RESPONSE, validateResponse);

        ThreeDSecure.onActivityResult(mFragment, RESULT_OK, data);

        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.canceled"));
    }

    @Test
    public void onActivityResult_whenCardinalCardVerificationErrors_sendsAnalyticsEvent() {
        mFragment = mMockFragmentBuilder.build();

        ValidateResponse validateResponse = mock(ValidateResponse.class);
        when(validateResponse.getActionCode()).thenReturn(CardinalActionCode.ERROR);

        Intent data = new Intent();
        data.putExtra(ThreeDSecureActivity.EXTRA_VALIDATION_RESPONSE, validateResponse);

        ThreeDSecure.onActivityResult(mFragment, RESULT_OK, data);

        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.failed"));
    }

    @Test
    public void authenticateCardinalJWT_whenSuccess_sendsAnalyticsEvent() throws JSONException {
        ThreeDSecureLookup threeDSecureLookup = ThreeDSecureLookup.fromJson(stringFromFixture("three_d_secure/lookup_response_with_version_number2.json"));

        mMockFragmentBuilder.successResponse(stringFromFixture("three_d_secure/authentication_response.json"));
        mFragment = mMockFragmentBuilder.build();

        ThreeDSecure.authenticateCardinalJWT(mFragment, threeDSecureLookup, "jwt");

        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.upgrade-payment-method.started"));
        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.upgrade-payment-method.succeeded"));
    }

    @Test
    public void authenticateCardinalJWT_whenFailure_sendsAnalyticsEvent() throws JSONException {
        ThreeDSecureLookup threeDSecureLookup = ThreeDSecureLookup.fromJson(stringFromFixture("three_d_secure/lookup_response_with_version_number2.json"));

        mMockFragmentBuilder.successResponse(stringFromFixture("three_d_secure/authentication_response_with_error.json"));
        mFragment = mMockFragmentBuilder.build();

        ThreeDSecure.authenticateCardinalJWT(mFragment, threeDSecureLookup, "jwt");

        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.upgrade-payment-method.started"));
        verify(mFragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.upgrade-payment-method.errored"));
    }

    @Test
    public void authenticateCardinalJWT_whenFailureAndLiabilityShiftPossible_returnsCardNonce() throws JSONException {
        ThreeDSecureLookup threeDSecureLookup = ThreeDSecureLookup.fromJson(stringFromFixture("three_d_secure/2.0/lookup_response_without_liability_with_liability_shift_possible.json"));

        mMockFragmentBuilder.successResponse(stringFromFixture("three_d_secure/2.0/authentication_response_with_liability_shift_possible.json"));
        BraintreeFragment fragment = mMockFragmentBuilder.build();

        ThreeDSecure.authenticateCardinalJWT(fragment, threeDSecureLookup, "jwt");

        ArgumentCaptor<PaymentMethodNonce> captor = ArgumentCaptor.forClass(PaymentMethodNonce.class);
        verify(fragment).postCallback(captor.capture());

        CardNonce cardNonce = (CardNonce) captor.getValue();

        assertFalse(cardNonce.getThreeDSecureInfo().isLiabilityShifted());
        assertTrue(cardNonce.getThreeDSecureInfo().isLiabilityShiftPossible());
    }

    @Test
    public void authenticateCardinalJWT_whenFailureAndLiabilityShiftPossible_sendsAnalyticEvent() throws JSONException {
        ThreeDSecureLookup threeDSecureLookup = ThreeDSecureLookup.fromJson(stringFromFixture("three_d_secure/2.0/lookup_response_without_liability_with_liability_shift_possible.json"));

        mMockFragmentBuilder.successResponse(stringFromFixture("three_d_secure/2.0/authentication_response_with_liability_shift_possible.json"));
        BraintreeFragment fragment = mMockFragmentBuilder.build();

        ThreeDSecure.authenticateCardinalJWT(fragment, threeDSecureLookup, "jwt");

        verify(fragment).sendAnalyticsEvent(eq("three-d-secure.verification-flow.upgrade-payment-method.liability-shift-possible"));
    }
}
