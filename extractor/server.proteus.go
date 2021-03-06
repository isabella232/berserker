package extractor

import (
	"golang.org/x/net/context"
)

type extractorServiceServer struct {
	Service *Service
}

func NewExtractorServiceServer(limit uint64, maxGrpcMsgSize int) *extractorServiceServer {
	return &extractorServiceServer{NewService(limit, maxGrpcMsgSize)}
}
func (s *extractorServiceServer) Service_GetRepositoriesData(ctx context.Context, in *Request) (result *Service_GetRepositoriesDataResponse, err error) {
	result = new(Service_GetRepositoriesDataResponse)
	result.Result1, err = s.Service.GetRepositoriesData(in)
	return
}
func (s *extractorServiceServer) Service_GetRepositoryData(ctx context.Context, in *Request) (result *RepositoryData, err error) {
	result = new(RepositoryData)
	result, err = s.Service.GetRepositoryData(in)
	return
}
