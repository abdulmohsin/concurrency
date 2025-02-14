


@Service
public class PricingServiceImpl implements PricingService {

    public static final String PRICING_ENGINE_NO_RATES_FOUND_OR_EMPTY_RESPONSE_STATUS_CODE = "Pricing engine - no rates found or empty response , status code";
    @Autowired
    private PricingClient pricingClient;

    @Autowired
    private CalculatePriceRequestMapper calculatePriceRequestMapper;

    @Autowired
    private CargospotClientProvider cargospotClientProvider;
    
    @Override
    public CalculatePriceResponse getPricingDetails(AirwaybillContext context) {
        log(Level.DEBUG,"CRA-Price request:::::: {}",context.getCalcIataRateRequest());
        PricingParameter pricingParam = createPricingEngineRequest(context);
        log(Level.DEBUG,"PricingParameter request:::::: {}",pricingParam);
        ResponseEntity<PricedResource> response = pricingClient.getPricingInfo(pricingParam);
        log(Level.DEBUG,"PricedResource response:::::: {} Status Code ::: {}",response);
        if (response.getStatusCode().is2xxSuccessful() && response.hasBody()) {
            return createResponse(context, response);
        } else {
            throw new ApiErrorWrapperException(raiseError(PRICING_ENGINE_NO_RATES_FOUND_OR_EMPTY_RESPONSE_STATUS_CODE + ":" +
                response.getStatusCode() + " , response :" + LogUtil.getJsonString(response)));
        }
    }

    private ApiError raiseError(String errorMessage) {
        ApiError apiError = new ApiError();
        GlobalMessage globalMessage = new GlobalMessage(null, null, errorMessage);
        apiError.getGlobalErrors().add(globalMessage);
        return apiError;
    }

    private PricingParameter createPricingEngineRequest(AirwaybillContext context) {
        if (CollectionUtils.isNotEmpty(context.getCalcIataRateRequest().getRatingLines())) {
            AtomicInteger line = new AtomicInteger(1);
            context.getCalcIataRateRequest().getRatingLines().stream().forEach(iataRatingLine -> {
                iataRatingLine.setLineNumber(String.valueOf(line.get()));
                line.getAndIncrement();
            });
        }
        PricingParameter pricingParam = calculatePriceRequestMapper.calcIataRequestToPricing(context.getCalcIataRateRequest(), context.getAirwayBillId());
        pricingParam.setRequestChannel(PricingParameter.RequestChannelEnum.AWB);
        if (context.getAirwaybillCommonModel().getConsolidation()!=null && context.getAirwaybillCommonModel().getConsolidation() 
            && context.getOperation()!=null && context.getOperation()== Operation.AWB_UPDATE){
            ResponseEntity<Integer> hawbCount= cargospotClientProvider.getCargospotClient(AirwaybillCargospotClient.class).getHawbCount(context.getAirwayBillId());
            if (hawbCount.hasBody()) {
                pricingParam.setHawbCount(hawbCount.getBody().intValue());
            } else {
                pricingParam.setHawbCount(0);
            }
            
        }
        pricingParam.setToCalculate(ToCalculateEnum.fromValue(context.getCalcIataRateRequest().getRequestType()));
        return pricingParam;
    }

    private CalculatePriceResponse createResponse(AirwaybillContext context, ResponseEntity<PricedResource> response) {
        List<PricedIATARateLine> pricingIataResponse = null;
        PricedResource craResponse = response.getBody();
        context.setCraResponse(craResponse);
        ChargeEstimate chargeEstimate = new ChargeEstimate();
        CalculatePriceResponse calculatePriceResponse = new CalculatePriceResponse();
        if (Objects.nonNull(craResponse) && Objects.nonNull(craResponse.getPricedIataRateLines())) {
            pricingIataResponse = craResponse.getPricedIataRateLines();
            if (CollectionUtils.isNotEmpty(pricingIataResponse)) {
                calculatePriceResponse.setRatingLines(getRatingLineData(pricingIataResponse, context));
            }
        }
        if (craResponse != null) {
            chargeEstimate.setFreightCharge(getFreightChargesData(craResponse));
            chargeEstimate.setGrossFreightCharge(getGrossFreightCharges(craResponse));
            chargeEstimate.setTax(getTaxDetails(craResponse));
            chargeEstimate.setNetFreightCharge(getNetFreightCharges(craResponse));
            chargeEstimate.setOtherCharges(getOtherCharges(craResponse));
            calculatePriceResponse.setPrice(chargeEstimate);
            getPriceLineDetails(calculatePriceResponse, craResponse);
            calculatePriceResponse.setTaxes(getTaxData(craResponse));
            setGlobalWarnings(craResponse, calculatePriceResponse, context.getWarningList());
            calculatePriceResponse.setCosts(new ArrayList<>()); // PE cost calculation pending : NHC-3217 , for now clearing the existing costs
            log(Level.DEBUG,"CalculatePrice response:::::: {}",calculatePriceResponse);
        }
        return calculatePriceResponse;
    }


    private void setGlobalWarnings(PricedResource craResponse, CalculatePriceResponse calculatePriceResponse, List<Warning> warningList) {
        List<aero.champ.pricing.engine.model.Warning> warningListPricing = craResponse.getGlobalWarnings();

        if (CollectionUtils.isNotEmpty(craResponse.getGlobalWarnings())) {
            setGlobalWarnings(warningList, warningListPricing);
        }
        if (CollectionUtils.isNotEmpty(craResponse.getPricedIataRateLines())) {
            setPricedIataRateLinesWarnings(craResponse, warningList);
        }

        if (CollectionUtils.isNotEmpty(craResponse.getPricedOtherCharges())) {
            setPricedOtherChargesWarnings(craResponse, warningList);
        }

        if (craResponse.getTaxDetails() != null && CollectionUtils.isNotEmpty(craResponse.getTaxDetails().getTaxCodeList())) {
            setTaxWarnings(craResponse, warningList);
        }

        if (CollectionUtils.isNotEmpty(craResponse.getPricedSellRates())) {
            setPricedSellRatesWarnings(craResponse, warningList);
        }

        calculatePriceResponse.setGlobalWarnings(warningList);
    }

    private void setGlobalWarnings(List<Warning> warningList, List<aero.champ.pricing.engine.model.Warning> warningListPricing) {
        warningListPricing.forEach(warning1 -> {
            if (StringUtils.isNotEmpty(warning1.getMessage())) {
                Warning warning = new Warning();
                warning.setMessage(warning1.getMessage());
                warningList.add(warning);
            }
        });
    }

    private void setPricedSellRatesWarnings(PricedResource craResponse, List<Warning> warningList) {
        craResponse.getPricedSellRates().forEach(pricedSellRate -> {
            if (CollectionUtils.isNotEmpty(pricedSellRate.getWarningMessage())) {
                pricedSellRate.getWarningMessage().forEach(warning1 -> {
                    Warning warning = new Warning();
                    warning.setMessage(warning1.getMessage());
                    warningList.add(warning);
                });
            }
        });
    }

    private void setTaxWarnings(PricedResource craResponse, List<Warning> warningList) {
        craResponse.getTaxDetails().getTaxCodeList().forEach(taxCode -> {
            if (StringUtils.isNotEmpty(taxCode.getWarningMessage())) {
                Warning warning = new Warning();
                warning.setMessage(taxCode.getWarningMessage());
                warningList.add(warning);
            }
        });
    }

    private void setPricedOtherChargesWarnings(PricedResource craResponse, List<Warning> warningList) {
        craResponse.getPricedOtherCharges().forEach(pricedOtherCharge -> {
            if (StringUtils.isNotEmpty(pricedOtherCharge.getWarningMessage())) {
                Warning warning = new Warning();
                warning.setMessage(pricedOtherCharge.getWarningMessage());
                warningList.add(warning);
            }
        });
    }

    private void setPricedIataRateLinesWarnings(PricedResource craResponse, List<Warning> warningList) {
        craResponse.getPricedIataRateLines().forEach(pricedIATARateLine -> {
            if (StringUtils.isNotEmpty(pricedIATARateLine.getWarningMessage())) {
                Warning warning = new Warning();
                warning.setMessage(pricedIATARateLine.getWarningMessage());
                warningList.add(warning);
            }
        });
    }


    private List<TaxResource> getTaxData(PricedResource craResponse) {
        List<TaxResource> taxResourceList = new ArrayList<>();
        int count=1;
        if (craResponse.getTaxDetails() != null && CollectionUtils.isNotEmpty(craResponse.getTaxDetails().getTaxCodeList())) {
            for (TaxCode taxCode : craResponse.getTaxDetails().getTaxCodeList()) {
                TaxResource taxResource = new TaxResource();
                taxResource.setTaxCode(taxCode.getTaxCodes());
                taxResource.setTaxLiability(taxCode.getTaxLiability());
                taxResource.setLine(count);
                setTaxResourceBaseTaxAmt(taxCode, taxResource);
                taxResourceList.add(taxResource);
                count++;
            }
        }
        return taxResourceList;
    }

    private void getPriceLineDetails(CalculatePriceResponse calculatePriceResponse, PricedResource craResponse) {
        List<PriceLine> priceLineList = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(craResponse.getPricedSellRates())) {
            for (PricedSellRate pricedSellRate : craResponse.getPricedSellRates()) {
                PriceLine priceLine = new PriceLine();
                short line =1;
                if (StringUtils.isNotEmpty(pricedSellRate.getLineNumber())){
                    line=Short.parseShort(pricedSellRate.getLineNumber());
                    priceLine.setLine(line);
                } else {
                    throw new ApiErrorWrapperException(raiseError( "Price Sell Rate line number not coming," +
                        " response :" + LogUtil.getJsonString(craResponse)));
                }

                PriceRate priceRate = new PriceRate();
                priceRate.setPriceType(AirwaybillPriceTypeEnum.SELL_PRICE);
                PriceRateDetail priceRateDetail = new PriceRateDetail();
                if (pricedSellRate.getSequenceId() != null) {
                    priceRateDetail.setPriceSequence(pricedSellRate.getSequenceId().longValue());
                }

                priceRateDetail.setConversionRate(pricedSellRate.getExchangeRate());
                priceRateDetail.setRateClass(pricedSellRate.getRateClass());
                priceRateDetail.setRateCurrency(pricedSellRate.getPriceCurrencyCode());
                if (pricedSellRate.getRate() != null && pricedSellRate.getRate().getAmount() != null) {
                    priceRateDetail.setNetRate(pricedSellRate.getRate().getAmount());
                    priceRateDetail.setRatePerQuantity(pricedSellRate.getRate().getAmount());
                }
                setPriceRateChargeWt(pricedSellRate, priceRateDetail);
                setPriceRateMinAmtRatePerQuant(pricedSellRate, priceRateDetail);
                priceRateDetail.setRateTotalAmount(pricedSellRate.getTotalAmount());
                priceRateDetail.setRateRemarks(pricedSellRate.getPriceReferenceSeq());
                if(StringUtils.isNotEmpty(pricedSellRate.getPriceReferenceType())) {
                    priceRateDetail.setAdhocType(AdhocEnumType.fromValue(pricedSellRate.getPriceReferenceType()));
                }
                priceRate.setPriceRateDetails(priceRateDetail);
                priceLine.getPriceRates().add(priceRate);

                if (CollectionUtils.isNotEmpty(craResponse.getPricedPublishedRates()) && line <= craResponse.getPricedPublishedRates().size()) {
                    populatePublishedLine(craResponse.getPricedPublishedRates().get(line - 1), priceLine);
                }
                populateAirwayBillPriceDetails(pricedSellRate, priceLine);
                priceLineList.add(priceLine);
                
            }

        }
        calculatePriceResponse.setPriceLines(priceLineList);
    }

    private void populateAirwayBillPriceDetails(PricedSellRate pricedSellRate, PriceLine priceLine) {
        if (pricedSellRate != null) {
            AirwaybillPrice airwaybillPrice = new AirwaybillPrice();

            Weight chargeableWeight = new Weight();
            if (pricedSellRate.getChargeableWeight() != null && pricedSellRate.getChargeableWeight().getAmount() != null) {
                chargeableWeight.setAmount(pricedSellRate.getChargeableWeight().getAmount());
                if (pricedSellRate.getChargeableWeight().getUnit() != null) {
                    chargeableWeight.setUnit(pricedSellRate.getChargeableWeight().getUnit().getValue());
                }
            }
            Weight weight = new Weight();
            if (pricedSellRate.getWeight() != null && pricedSellRate.getWeight().getAmount() != null) {
                weight.setAmount(pricedSellRate.getWeight().getAmount());
                if (pricedSellRate.getWeight().getUnit() != null) {
                    weight.setUnit(pricedSellRate.getWeight().getUnit().getValue());
                }
            }
            airwaybillPrice.setAirWaybillPriceChargeableWeight(chargeableWeight);
            airwaybillPrice.setAirWaybillPriceGrossWeight(weight);
            airwaybillPrice.setAirWaybillPriceCommodity(pricedSellRate.getCommodity());
            airwaybillPrice.setAirWaybillPriceClass(pricedSellRate.getPriceClassCode());
            airwaybillPrice.setAirWaybillPriceProductCode(pricedSellRate.getProductCode());
            airwaybillPrice.setPriceOriginCode(pricedSellRate.getOriginAirportCode());
            airwaybillPrice.setPriceDestinationCode(pricedSellRate.getDestinationAirportCode());
            airwaybillPrice.setAirWaybillPriceContourCode(pricedSellRate.getContourCode());
            airwaybillPrice.setAirWaybillPriceUldNumber(pricedSellRate.getUldNumber());
            airwaybillPrice.setAirWaybillPriceUldType(pricedSellRate.getUldType());

            if (pricedSellRate.getPieces() != null) {
                airwaybillPrice.setAirWaybillPricePieces(pricedSellRate.getPieces());
            }
            priceLine.setAirWaybillPrice(airwaybillPrice);
        }
    }

    private void populatePublishedLine(PricedPublishedRate pricedPublishedRate, PriceLine priceLine) {

        if (isBKCase(pricedPublishedRate)) {
            PriceRate priceRate = getPublishedRateBK(pricedPublishedRate);
            priceLine.getPriceRates().add(priceRate);
        } else {
            PriceRate priceRate = getPublishedRate(pricedPublishedRate);
            priceLine.getPriceRates().add(priceRate);
        }

    }

    private PriceRate getPublishedRateBK(PricedPublishedRate pricedPublishedRate) {
        PriceRate priceRate = new PriceRate();
        priceRate.setPriceType(AirwaybillPriceTypeEnum.PUBLISHED);
        PriceRateDetail priceRateDetail = new PriceRateDetail();
        if (pricedPublishedRate.getTotalAmount() != null) {
            priceRateDetail.setRateTotalAmount(pricedPublishedRate.getTotalAmount());
        }
        if (pricedPublishedRate.getRateClass().equals("B") && pricedPublishedRate.getMinimumBasic() != null) {
            priceRateDetail.setRateMinimumAmount(pricedPublishedRate.getMinimumBasic());
        }
        if (pricedPublishedRate.getRateClass().equals("K") && pricedPublishedRate.getRate() != null) {
            priceRateDetail.setRatePerQuantity(pricedPublishedRate.getRate());

        }

        priceRateDetail.setRateClass("B");
        priceRateDetail.setRateCurrency(pricedPublishedRate.getPriceCurrencyCode());
        if(StringUtils.isNotEmpty(pricedPublishedRate.getPriceReferenceType())) {
            priceRateDetail.setAdhocType(AdhocEnumType.fromValue(pricedPublishedRate.getPriceReferenceType()));
        }
        priceRate.setPriceRateDetails(priceRateDetail);

        return priceRate;

    }

    private boolean isBKCase(PricedPublishedRate pricedPublishedRates) {
        if (StringUtils.isNotEmpty(pricedPublishedRates.getRateClass())) {
            return pricedPublishedRates.getRateClass().equals("B") && pricedPublishedRates.getRateClass().equals("K");
        } else {
            return false;
        }
    }


    private PriceRate getPublishedRate(PricedPublishedRate pricedPublishedRate) {
        PriceRate priceRate = new PriceRate();
        priceRate.setPriceType(AirwaybillPriceTypeEnum.PUBLISHED);
        PriceRateDetail priceRateDetail = new PriceRateDetail();
        priceRateDetail.setRateClass(pricedPublishedRate.getRateClass());
        priceRateDetail.setRateCurrency(pricedPublishedRate.getPriceCurrencyCode());
        if(StringUtils.isNotEmpty(pricedPublishedRate.getPriceReferenceType())) {
            priceRateDetail.setAdhocType(AdhocEnumType.fromValue(pricedPublishedRate.getPriceReferenceType()));
        }
        if (pricedPublishedRate.getRate() != null) {
            priceRateDetail.setNetRate(pricedPublishedRate.getRate());
            // U , B - both
            if (StringUtils.isNotEmpty(pricedPublishedRate.getRateClass()) && (
                pricedPublishedRate.getRateClass().equalsIgnoreCase("U") || 
                    pricedPublishedRate.getRateClass().equalsIgnoreCase("B"))) {
                priceRateDetail.setRatePerQuantity(pricedPublishedRate.getRate());
                priceRateDetail.setRateMinimumAmount(pricedPublishedRate.getMinimumBasic());
            }
            else if (StringUtils.isNotEmpty(pricedPublishedRate.getRateClass()) && !pricedPublishedRate.getRateClass().equalsIgnoreCase("M")) {
                priceRateDetail.setRatePerQuantity(pricedPublishedRate.getRate());
            } else {
                priceRateDetail.setRateMinimumAmount(pricedPublishedRate.getMinimumBasic());
            }
        }

        if (pricedPublishedRate.getChargeableWeight() != null) {
            Weight weight = new Weight();
            weight.setAmount(pricedPublishedRate.getChargeableWeight().getAmount());
            if (pricedPublishedRate.getChargeableWeight().getUnit() != null) {
                weight.setUnit(pricedPublishedRate.getChargeableWeight().getUnit().getValue());
            }
            priceRateDetail.setRateChargeWeight(weight);
        }

        if (pricedPublishedRate.getTotalAmount() != null) {
            priceRateDetail.setRateTotalAmount(pricedPublishedRate.getTotalAmount());
        }
        priceRate.setPriceRateDetails(priceRateDetail);

        return priceRate;

    }


    private void setPriceRateChargeWt(PricedSellRate pricedSellRate, PriceRateDetail priceRateDetail) {
        if (pricedSellRate.getChargeableWeight() != null && pricedSellRate.getChargeableWeight().getAmount() != null) {
            Weight weight = new Weight();
            weight.setAmount(pricedSellRate.getChargeableWeight().getAmount());
            if (pricedSellRate.getChargeableWeight().getUnit() != null) {
                weight.setUnit(pricedSellRate.getChargeableWeight().getUnit().getValue());
            }
            priceRateDetail.setRateChargeWeight(weight);
        }
    }

    private void setPriceRateMinAmtRatePerQuant(PricedSellRate pricedSellRate, PriceRateDetail priceRateDetail) {
        if (StringUtils.isNotEmpty(pricedSellRate.getAddOn())) {
            priceRateDetail.setAddOnRatePerQuantity(Float.valueOf(pricedSellRate.getAddOn()));
        }
        if (pricedSellRate.getMinimumBasic() != null && pricedSellRate.getMinimumBasic().getAmount() != null) {
            priceRateDetail.setRateMinimumAmount(pricedSellRate.getMinimumBasic().getAmount());
        }
    }

    private List<OtherCharge> getOtherCharges(PricedResource craResponse) {
        List<OtherCharge> otherChargeList = new ArrayList<>();
        int count=1;
        if (CollectionUtils.isNotEmpty(craResponse.getPricedOtherCharges())) {
            for (PricedOtherCharge pricedOtherCharge : craResponse.getPricedOtherCharges()) {
                OtherCharge otherCharge = new OtherCharge();
                setOtherChargeCharge(pricedOtherCharge, otherCharge);
                if (pricedOtherCharge.getExclude() != null) {
                    otherCharge.setExclude(pricedOtherCharge.getExclude().getValue());
                }
                otherCharge.setCode(pricedOtherCharge.getCode());
                if (pricedOtherCharge.getSequenceId() != null) {
                    otherCharge.setCalcSequence(pricedOtherCharge.getSequenceId().longValue());
                }
                setOtherChargePrepaidCollect(pricedOtherCharge, otherCharge);
                otherCharge.setLine(count);
                otherChargeList.add(otherCharge);
                count++;

            }
        }
        return otherChargeList;
    }

    private void setOtherChargeCharge(PricedOtherCharge pricedOtherCharge, OtherCharge otherCharge) {
        if (pricedOtherCharge.getAmount() != null) {
            AmountCharges amountCharges = new AmountCharges();
            amountCharges.setAmount(BigDecimal.valueOf(pricedOtherCharge.getAmount().getAmount()));
            Currency currency = new Currency();
            currency.setCode(pricedOtherCharge.getAmount().getCurrencyCode());
            amountCharges.setCurrency(currency);
            otherCharge.setCharge(amountCharges);
        }
    }

    private void setOtherChargePrepaidCollect(PricedOtherCharge pricedOtherCharge, OtherCharge otherCharge) {
        if (pricedOtherCharge.getType() != null) {
            if (pricedOtherCharge.getType() == PricedOtherCharge.TypeEnum.COLLECT) {
                otherCharge.setPrepaidCollect("C");
            } else {
                otherCharge.setPrepaidCollect("P");
            }
        }
    }

    private GrossFreightCharge getGrossFreightCharges(PricedResource craResponse) {
        GrossFreightCharge grossFreightCharge = new GrossFreightCharge();
        if (craResponse.getChargesSummary() != null) {
            ChargesSummary chargesSummary = craResponse.getChargesSummary();
            setGrossWeightCharge(chargesSummary, grossFreightCharge);
            setGrossTotalCharge(chargesSummary, grossFreightCharge);
            setGrossValuationCharge(chargesSummary, grossFreightCharge);
            setGrossFreightDueAgent(chargesSummary, grossFreightCharge);
            setGrossFreightDueCarrier(chargesSummary, grossFreightCharge);
        }
        return grossFreightCharge;
    }

    private void setGrossFreightDueCarrier(ChargesSummary chargesSummary, GrossFreightCharge grossFreightCharge) {
        if (chargesSummary.getOtherChargesDueCarrier() != null) {
            PrepaidCollectAmounts prepaidCollectAmounts = new PrepaidCollectAmounts();
            if (chargesSummary.getOtherChargesDueCarrier().getCollect() != null) {
                AmountCharges amountCharges = new AmountCharges();
                amountCharges.setAmount(BigDecimal.valueOf(chargesSummary.getOtherChargesDueCarrier().getCollect().getAmount()));
                prepaidCollectAmounts.setCollect(amountCharges);
            }
            if (chargesSummary.getOtherChargesDueCarrier().getPrepaid() != null) {
                AmountCharges amountCharges = new AmountCharges();
                amountCharges.setAmount(BigDecimal.valueOf(chargesSummary.getOtherChargesDueCarrier().getPrepaid().getAmount()));
                prepaidCollectAmounts.setPrepaid(amountCharges);
            }
            grossFreightCharge.setOtherChargesDueCarrier(prepaidCollectAmounts);
        }
    }

    private NetFreightCharge getNetFreightCharges(PricedResource craResponse) {
        NetFreightCharge netFreightCharge = new NetFreightCharge();
        Currency currency = new Currency();
        currency.setCode(craResponse.getAirwaybillCurrencyCode());
        if (craResponse.getIaTACommission() != null) {
            netFreightCharge.setIataCommissionPercentage(BigDecimal.valueOf(craResponse.getIaTACommission().getIaTAPercentage()));
            AmountCharges amountChargesIataCommision = new AmountCharges();
            if (craResponse.getIaTACommission().getCommissionAmount() != null) {
                amountChargesIataCommision.setAmount(BigDecimal.valueOf(craResponse.getIaTACommission().getCommissionAmount().getAmount()));
            }
            amountChargesIataCommision.setCurrency(currency);
            netFreightCharge.setIataCommission(amountChargesIataCommision);
        }

        AmountCharges amountChargesNetTotal = new AmountCharges();
        if (craResponse.getNetNetAmount() != null && craResponse.getNetNetAmount().getAmount() != null) {
            amountChargesNetTotal.setAmount(BigDecimal.valueOf(craResponse.getNetNetAmount().getAmount().getAmount()));
        }
        amountChargesNetTotal.setCurrency(currency);
        netFreightCharge.setNetTotal(amountChargesNetTotal);

        AmountCharges amountChargesNetFreightTotal = new AmountCharges();
        if (craResponse.getNetAmount() != null) {
            amountChargesNetFreightTotal.setAmount(BigDecimal.valueOf(craResponse.getNetAmount().getAmount()));
        }
        amountChargesNetFreightTotal.setCurrency(currency);
        netFreightCharge.setNetFreightTotal(amountChargesNetFreightTotal);
        if (craResponse.getNetRate() != null) {
            AmountCharges amountChargesNetRate = new AmountCharges();
            amountChargesNetRate.setAmount(BigDecimal.valueOf(craResponse.getNetRate()));
            netFreightCharge.setNetFreightRate(amountChargesNetRate);
        }


        if (craResponse.getIncentive() != null && craResponse.getIncentive().getIncentiveAmount() != null) {
            AmountCharges amountChargesIncentive = new AmountCharges();
            amountChargesIncentive.setAmount(BigDecimal.valueOf(craResponse.getIncentive().getIncentiveAmount().getAmount()));
            amountChargesIncentive.setCurrency(currency);
            netFreightCharge.setIncentive(amountChargesIncentive);
        }
        return netFreightCharge;
    }


    private void setGrossFreightDueAgent(ChargesSummary chargesSummary, GrossFreightCharge grossFreightCharge) {
        if (chargesSummary.getOtherChargesDueAgent() != null) {
            PrepaidCollectAmounts prepaidCollectAmounts = new PrepaidCollectAmounts();
            if (chargesSummary.getOtherChargesDueAgent().getCollect() != null) {
                AmountCharges amountCharges = new AmountCharges();
                amountCharges.setAmount(BigDecimal.valueOf(chargesSummary.getOtherChargesDueAgent().getCollect().getAmount()));
                prepaidCollectAmounts.setCollect(amountCharges);
            }
            if (chargesSummary.getOtherChargesDueAgent().getPrepaid() != null) {
                AmountCharges amountCharges = new AmountCharges();
                amountCharges.setAmount(BigDecimal.valueOf(chargesSummary.getOtherChargesDueAgent().getPrepaid().getAmount()));
                prepaidCollectAmounts.setPrepaid(amountCharges);
            }
            grossFreightCharge.setOtherChargesDueAgent(prepaidCollectAmounts);
        }
    }

    private Tax getTaxDetails(PricedResource craResponse) {
        Tax tax = new Tax();
        if (craResponse.getTaxDetails() != null) {
            TaxDetails taxDetails = craResponse.getTaxDetails();
            AmountCharges amountChargesAgent = new AmountCharges();
            if (taxDetails.getTaxAmountAgent() != null && taxDetails.getTaxAmountAgent().getAmount() != null) {
                amountChargesAgent.setAmount(BigDecimal.valueOf(taxDetails.getTaxAmountAgent().getAmount()));
            }
            tax.setDueAgent(amountChargesAgent);

            AmountCharges amountChargesCarrier = new AmountCharges();
            if (taxDetails.getTaxAmountCarrier() != null && taxDetails.getTaxAmountCarrier().getAmount() != null) {
                amountChargesCarrier.setAmount(BigDecimal.valueOf(taxDetails.getTaxAmountCarrier().getAmount()));
            }
            tax.setDueCarrier(amountChargesCarrier);
        }
        return tax;
    }

    private void setGrossValuationCharge(ChargesSummary chargesSummary, GrossFreightCharge grossFreightCharge) {
        if (chargesSummary.getValuationCharge() != null) {
            PrepaidCollectAmounts prepaidCollectAmounts = new PrepaidCollectAmounts();
            if (chargesSummary.getValuationCharge().getCollect() != null) {
                AmountCharges amountCharges = new AmountCharges();
                amountCharges.setAmount(BigDecimal.valueOf(chargesSummary.getValuationCharge().getCollect().getAmount()));
                prepaidCollectAmounts.setCollect(amountCharges);
            }
            if (chargesSummary.getValuationCharge().getPrepaid() != null) {
                AmountCharges amountCharges = new AmountCharges();
                amountCharges.setAmount(BigDecimal.valueOf(chargesSummary.getValuationCharge().getPrepaid().getAmount()));
                prepaidCollectAmounts.setPrepaid(amountCharges);
            }
            grossFreightCharge.setValuationCharge(prepaidCollectAmounts);
        }
    }

    private void setGrossTotalCharge(ChargesSummary chargesSummary, GrossFreightCharge grossFreightCharge) {
        if (chargesSummary.getTotalCharges() != null) {
            PrepaidCollectAmounts prepaidCollectAmounts = new PrepaidCollectAmounts();
            if (chargesSummary.getTotalCharges().getCollect() != null) {
                AmountCharges amountCharges = new AmountCharges();
                amountCharges.setAmount(BigDecimal.valueOf(chargesSummary.getTotalCharges().getCollect().getAmount()));
                prepaidCollectAmounts.setCollect(amountCharges);
            }
            if (chargesSummary.getTotalCharges().getPrepaid() != null) {
                AmountCharges amountCharges = new AmountCharges();
                amountCharges.setAmount(BigDecimal.valueOf(chargesSummary.getTotalCharges().getPrepaid().getAmount()));
                prepaidCollectAmounts.setPrepaid(amountCharges);
            }
            grossFreightCharge.setTotalCharges(prepaidCollectAmounts);
        }
    }

    private void setGrossWeightCharge(ChargesSummary chargesSummary, GrossFreightCharge grossFreightCharge) {
        if (chargesSummary.getWeightCharge() != null) {
            PrepaidCollectAmounts prepaidCollectAmounts = new PrepaidCollectAmounts();
            if (chargesSummary.getWeightCharge().getCollect() != null) {
                AmountCharges amountCharges = new AmountCharges();
                amountCharges.setAmount(BigDecimal.valueOf(chargesSummary.getWeightCharge().getCollect().getAmount()));
                prepaidCollectAmounts.setCollect(amountCharges);
            }
            if (chargesSummary.getWeightCharge().getPrepaid() != null) {
                AmountCharges amountCharges = new AmountCharges();
                amountCharges.setAmount(BigDecimal.valueOf(chargesSummary.getWeightCharge().getPrepaid().getAmount()));
                prepaidCollectAmounts.setPrepaid(amountCharges);
            }
            grossFreightCharge.setWeightCharge(prepaidCollectAmounts);
        }
    }

    private FreightCharge getFreightChargesData(PricedResource craResponse) {
        FreightCharge freightCharge = new FreightCharge();
        if ((Objects.nonNull(craResponse.getPricedSellRates())) && (CollectionUtils.isNotEmpty(craResponse.getPricedSellRates()))) {
            for (PricedSellRate pricedSellRate : craResponse.getPricedSellRates()) {
                AmountCharges amountChargesRate = new AmountCharges();
                if (pricedSellRate.getRate() != null) {
                    amountChargesRate.setAmount(BigDecimal.valueOf(pricedSellRate.getRate().getAmount()));
                }
                freightCharge.setSellRate(amountChargesRate);
                AmountCharges amountChargesTotal = new AmountCharges();
                amountChargesTotal.setAmount(BigDecimal.valueOf(pricedSellRate.getTotalAmount()));
                freightCharge.setSellTotal(amountChargesTotal);

            }
        }
        return freightCharge;
    }

    private List<IataRatingLine> getRatingLineData(List<PricedIATARateLine> pricingIataResponse, AirwaybillContext context) {
        List<IataRatingLine> ratingLines = new ArrayList<>();
        for (PricedIATARateLine iataRate : pricingIataResponse) {

            IataRatingLine iataData = new IataRatingLine();
            iataData.setLineNumber(iataRate.getLineNumber());
            iataData.setPropertyClass(Objects.nonNull(iataRate.getRateClass()) ? iataRate.getRateClass() : "");
            iataData.setCommodity(Objects.nonNull(iataRate.getCommodity()) ? iataRate.getCommodity() : "");
            setRatingLinesPieces(iataRate, iataData);
            setIataDataWeightChargeWt(iataRate, iataData);
            setIataDataRateTotal(iataRate, iataData);
            iataData.setUldType(iataRate.getUldType());
            if (CollectionUtils.isNotEmpty(context.getCalcIataRateRequest().getRatingLines()) && StringUtils.isNotEmpty(iataData.getLineNumber())) {
                Optional<aero.champ.csp.airwaybill.rest.api.request.IataRatingLine> matchedReqRatingRecordOpt = context.getCalcIataRateRequest().
                        getRatingLines().stream().filter(r -> r.getLineNumber() != null && r.getLineNumber().equals(iataData.getLineNumber())).findAny();

                if (matchedReqRatingRecordOpt.isPresent()) {
                    aero.champ.csp.airwaybill.rest.api.request.IataRatingLine matchedReqRatingRecord = matchedReqRatingRecordOpt.get();
                    iataData.setService(matchedReqRatingRecord.getService());
                    iataData.setNatureOfGoods(matchedReqRatingRecord.getNatureOfGoods());
                    iataData.setGoodsType(matchedReqRatingRecord.getGoodsType());
                    iataData.setManuallyChanged(matchedReqRatingRecord.isManuallyChanged());
                }
            }
            ratingLines.add(iataData);

        }
        return ratingLines;
    }

    private static void setRatingLinesPieces(PricedIATARateLine iataRate, IataRatingLine iataData) {
        if (iataRate.getPieces() != null && iataRate.getPieces()!= 0) {
            iataData.setPieces(iataRate.getPieces());
        } else {
            iataData.setPieces(null);
        }
    }

    private void setIataDataRateTotal(PricedIATARateLine iataRate, IataRatingLine iataData) {
        if (iataRate.getTotal() != null) {
            AmountCharges amountCharge = new AmountCharges();
            Currency currency = new Currency();
            amountCharge.setAmount(BigDecimal.valueOf(iataRate.getTotal().getAmount()));
            currency.setCode(iataRate.getTotal().getCurrencyCode());
            amountCharge.setCurrency(currency);
            iataData.setTotal(amountCharge);
        }
        if (iataRate.getRate() != null) {
            AmountCharges amountCharge1 = new AmountCharges();
            Currency currency = new Currency();
            amountCharge1.setAmount(BigDecimal.valueOf(iataRate.getRate().getAmount()));
            currency.setCode(iataRate.getRate().getCurrencyCode());
            amountCharge1.setCurrency(currency);
            iataData.setRate(amountCharge1);
        }
    }

    private void setIataDataWeightChargeWt(PricedIATARateLine iataRate, IataRatingLine iataData) {
        if (iataRate.getChargeableWeight() != null) {
            IataWeight chargeableWeight = new IataWeight();
            IataWeight weight = new IataWeight();
            if (iataRate.getChargeableWeight() != null) {
                chargeableWeight.setAmount(iataRate.getChargeableWeight().getAmount());
                if (iataRate.getChargeableWeight().getUnit() != null) {
                    chargeableWeight.setUnit(iataRate.getChargeableWeight().getUnit().toString());
                }
                iataData.setChargeableWeight(chargeableWeight);
            }
            if (iataRate.getWeight() != null && iataRate.getWeight().getAmount() != null) {
                weight.setAmount(iataRate.getWeight().getAmount());
                if (iataRate.getWeight().getUnit() != null) {
                    weight.setUnit(iataRate.getWeight().getUnit().getValue());
                }

                iataData.setWeight(weight);
            }

        }
    }

    private void setTaxResourceBaseTaxAmt(TaxCode taxCode, TaxResource taxResource) {
        if (taxCode.getBaseAmount() != null) {
            taxResource.setTaxBaseAmount(taxCode.getBaseAmount().doubleValue());
        }
        if (taxCode.getTaxAmount() != null) {
            taxResource.setAmount(taxCode.getTaxAmount().doubleValue());
        }
        if (taxCode.getSequenceId() != null) {
            taxResource.setTaxSeq(taxCode.getSequenceId().longValue());
        }
    }
}
